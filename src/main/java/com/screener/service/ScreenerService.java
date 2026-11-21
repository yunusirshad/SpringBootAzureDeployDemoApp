package com.screener.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.screener.config.AppConfig;
import com.screener.config.ScreenerProperties;
import com.screener.data.JsonStore;
import com.screener.data.MarketData;
import com.screener.model.Fundamentals;
import com.screener.model.GateResult;
import com.screener.model.GexLevels;
import com.screener.model.ListedSymbol;
import com.screener.model.Quality;
import com.screener.model.Quote;
import com.screener.model.ScreenResult;
import com.screener.model.ScreenRow;
import com.screener.model.ZoyaCheck;

/**
 * The screen: Nasdaq + NYSE universe, the price band and market cap from a batch quote, then the full
 * ratio gates per name, then a Zoya check on each name that passed the ratios. Quality is scored on every
 * row and never removes one. GEX levels are added to the rows that make the list.
 */
@Service
public class ScreenerService {
    private static final Logger log = LoggerFactory.getLogger(ScreenerService.class);
    private static final String FILE = "last-screen.json";

    private final MarketData market;
    private final RuleEngine rules;
    private final QualityScorer quality;
    private final GexCalculator gexCalc;
    private final ZoyaService zoya;
    private final CoreStocksService core;
    private final ScreenerProperties props;
    private final JsonStore store;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ScreenResult latest;

    public ScreenerService(MarketData market, RuleEngine rules, QualityScorer quality, GexCalculator gexCalc,
                           ZoyaService zoya, CoreStocksService core, ScreenerProperties props, JsonStore store, Clock clock) {
        this.market = market;
        this.rules = rules;
        this.quality = quality;
        this.gexCalc = gexCalc;
        this.zoya = zoya;
        this.core = core;
        this.props = props;
        this.store = store;
        this.clock = clock;
        this.latest = store.read(FILE, new TypeReference<ScreenResult>() {}).orElse(ScreenResult.empty());
    }

    public ScreenResult latest() {
        return latest;
    }

    public boolean isRunning() {
        return running.get();
    }

    public ScreenResult run(boolean refreshZoya) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("A screen is already running.");
        try {
            latest = doRun(refreshZoya);
            store.write(FILE, latest);
            core.merge(latest.rows());
            latest.rejected().forEach(core::refreshIfPresent);
            return latest;
        } finally {
            running.set(false);
        }
    }

    private ScreenResult doRun(boolean refreshZoya) {
        List<String> warnings = new ArrayList<>();
        List<ListedSymbol> universe = market.universe();
        List<String> symbols = universe.stream().map(ListedSymbol::symbol).distinct().toList();
        Map<String, Quote> quotes = market.quotes(symbols);
        if (quotes.size() < symbols.size()) warnings.add((symbols.size() - quotes.size()) + " symbols had no quote.");

        List<Quote> band = quotes.values().stream()
                .filter(q -> rules.inPriceBand(q.price()) && rules.overMarketCap(q.marketCap()))
                .sorted(Comparator.comparing(Quote::marketCap, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int max = props.getData().getMaxCandidates();
        List<Quote> candidates = band.size() > max ? band.subList(0, max) : band;
        if (band.size() > max) warnings.add("Only the " + max + " largest of " + band.size() + " names in the band were checked.");

        List<ScreenRow> all = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, props.getData().getThreads()));
        try {
            List<Future<Optional<ScreenRow>>> futures = new ArrayList<>();
            for (Quote q : candidates) futures.add(pool.submit(() -> evaluateQuietly(q.symbol(), refreshZoya, false, warnings)));
            for (Future<Optional<ScreenRow>> f : futures) {
                try {
                    f.get().ifPresent(all::add);
                } catch (Exception e) {
                    warnings.add("A row failed: " + e.getMessage());
                }
            }
            // GEX for the names that made the list.
            List<Future<ScreenRow>> gexed = new ArrayList<>();
            for (ScreenRow row : all) {
                if (row.passed()) gexed.add(pool.submit(() -> withGex(row)));
            }
            List<ScreenRow> passed = new ArrayList<>();
            for (Future<ScreenRow> f : gexed) {
                try {
                    passed.add(f.get());
                } catch (Exception e) {
                    warnings.add("GEX failed: " + e.getMessage());
                }
            }
            passed.sort(Comparator.comparing(ScreenRow::upside, Comparator.nullsLast(Comparator.reverseOrder())));
            List<ScreenRow> rejected = all.stream().filter(r -> !r.passed()).toList();
            int ratioPass = (int) all.stream().filter(ScreenRow::ratiosPass).count();
            int compliant = (int) all.stream().filter(r -> r.ratiosPass() && zoya.isFreshCompliant(r.zoya())).count();
            return new ScreenResult(clock.instant(), LocalDate.now(clock.withZone(AppConfig.NEW_YORK)), market.name(),
                    universe.size(), band.size(), all.size(), ratioPass, compliant, passed, rejected,
                    List.copyOf(warnings));
        } finally {
            pool.shutdown();
        }
    }

    private Optional<ScreenRow> evaluateQuietly(String symbol, boolean refreshZoya, boolean withGex, List<String> warnings) {
        try {
            return Optional.of(evaluate(symbol, refreshZoya, withGex));
        } catch (Exception e) {
            log.debug("{} skipped: {}", symbol, e.getMessage());
            synchronized (warnings) {
                if (warnings.size() < 50) warnings.add(symbol + ": " + e.getMessage());
            }
            return Optional.empty();
        }
    }

    /** One name through every gate, as the screen does it. Zoya is read only when the ratios pass, unless forced. */
    public ScreenRow evaluate(String symbol, boolean refreshZoya, boolean withGex) {
        Fundamentals f = market.fundamentals(symbol);
        List<GateResult> gates = rules.evaluate(f);
        boolean ratiosPass = gates.stream().allMatch(GateResult::pass);
        ZoyaCheck z = ratiosPass || refreshZoya ? zoya.check(symbol, refreshZoya)
                : new ZoyaCheck(symbol, "UNKNOWN", null, "NONE", "not checked: failed a ratio gate");
        ScreenRow row = buildRow(f, gates, ratiosPass, z, quality.score(f), null);
        return withGex ? withGex(row) : row;
    }

    private ScreenRow withGex(ScreenRow row) {
        GexLevels g;
        try {
            g = gexCalc.compute(market.optionChain(row.symbol(), props.getData().getGexExpirations()),
                    LocalDate.now(clock.withZone(AppConfig.NEW_YORK)));
        } catch (Exception e) {
            g = GexLevels.unavailable(row.symbol(), e.getMessage());
        }
        return new ScreenRow(row.symbol(), row.name(), row.exchange(), row.price(), row.marketCap(), row.targetMeanPrice(),
                row.upside(), row.debtToEquity(), row.debtToAssets(), row.debtToCapital(), row.cashToDebt(),
                row.revenueGrowth(), row.earningsDate(), row.gates(), row.ratiosPass(), row.zoya(), row.quality(), g,
                row.passed(), row.failedReasons(), row.updatedAt());
    }

    ScreenRow buildRow(Fundamentals f, List<GateResult> gates, boolean ratiosPass, ZoyaCheck z, Quality q, GexLevels g) {
        RuleEngine.Ratios x = rules.ratios(f);
        List<String> reasons = new ArrayList<>();
        for (GateResult gr : gates) {
            if (!gr.pass()) reasons.add(gr.gate() + (gr.value() == null ? " (no data)" : " " + gr.threshold() + " failed"));
        }
        boolean zoyaOk = !props.getRules().isRequireZoyaCompliant() || zoya.isFreshCompliant(z);
        if (ratiosPass && !zoyaOk) {
            reasons.add("Zoya " + z.status() + ("STALE".equals(z.source()) ? " (stale, over 24h)" : "")
                    + (z.error() != null ? " – " + z.error() : ""));
        }
        Double cd = x.cashToDebt() != null && x.cashToDebt().isInfinite() ? null : x.cashToDebt();
        return new ScreenRow(f.symbol(), f.name(), f.exchange(), f.price(), f.marketCap(), f.targetMeanPrice(),
                x.upside(), x.debtToEquity(), x.debtToAssets(), x.debtToCapital(), cd, f.revenueGrowth(),
                f.earningsDate(), gates, ratiosPass, z, q, g, ratiosPass && zoyaOk, reasons, clock.instant());
    }
}
