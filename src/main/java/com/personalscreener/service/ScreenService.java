package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.analysis.GexCalculator;
import com.personalscreener.analysis.LongScore;
import com.personalscreener.analysis.TrendClassifier;
import com.personalscreener.analysis.Indicators;
import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.data.*;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.*;
import com.personalscreener.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The whole screen: TradingView filters, Zoya, bar-by-bar analysis, the lists, GEX levels,
 * a 10-second price tick and the market pulse.
 */
@Service
public class ScreenService {

    private static final Logger log = LoggerFactory.getLogger(ScreenService.class);
    private static final String SNAPSHOT = "snapshot.json";
    private static final String GEX = "gex-cache.json";
    private static final List<String> INDEXES = List.of("SPY", "QQQ", "IWM");

    private final ScreenerProperties props;
    private final TradingViewClient tv;
    private final YahooClient yahoo;
    private final ZoyaService zoya;
    private final StockAnalyzer analyzer;
    private final WatchlistService watchlist;
    private final AlertService alerts;
    private final MarketClock clock;
    private final JsonStore store;

    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, GexLevels> gexCache;
    /** The day each name's chain was last asked for, so a name with no options is tried once a day, not every run. */
    private final Map<String, LocalDate> chainTried = new ConcurrentHashMap<>();
    /** Open interest is summed over the expiries in the next 45 days (at most 8). */
    static final int CHAIN_DAYS = 45, CHAIN_MAX_EXPIRIES = 8;
    private final List<Alert> recentlyRang = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot;
    private volatile Instant lastTick = Instant.EPOCH;
    private volatile Instant lastPulse = Instant.EPOCH;
    private volatile String lastChainRun;
    private int roundRobin = 0;

    public ScreenService(ScreenerProperties props, TradingViewClient tv, YahooClient yahoo, ZoyaService zoya,
                         StockAnalyzer analyzer, WatchlistService watchlist, AlertService alerts,
                         MarketClock clock, JsonStore store) {
        this.props = props;
        this.tv = tv;
        this.yahoo = yahoo;
        this.zoya = zoya;
        this.analyzer = analyzer;
        this.watchlist = watchlist;
        this.alerts = alerts;
        this.clock = clock;
        this.store = store;
        this.snapshot = store.read(SNAPSHOT, new TypeReference<Snapshot>() {}, new Snapshot());
        this.gexCache = new ConcurrentHashMap<>(store.read(GEX, new TypeReference<Map<String, GexLevels>>() {}, new HashMap<>()));
        this.lastChainRun = snapshot.lastChainRun;
    }

    public Snapshot snapshot() { return snapshot; }

    public boolean isRunning() { return running.get(); }

    // ---- schedules -------------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        pool.submit(() -> refresh(gexCache.isEmpty()));
    }

    /** Full screen every 30 minutes inside the Monday 7:00 AM - Friday 8:00 PM ET window. */
    @Scheduled(cron = "0 */30 * * * *", zone = "America/New_York")
    public void scheduledRefresh() {
        if (clock.inRefreshWindow()) refresh(false);
    }

    /** Option chains for every name at 8:00 AM ET each weekday, after the overnight open-interest update. */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "America/New_York")
    public void scheduledChains() {
        refreshChains(snapshot.rows.stream().map(r -> r.symbol).toList());
        rebuild(snapshot);
        store.write(SNAPSHOT, snapshot);
    }

    /** Price every 10 seconds while the extended session is open; once every 5 minutes otherwise. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void tick() {
        boolean live = clock.isExtendedSession(clock.now());
        if (!live && Duration.between(lastTick, Instant.now()).toMinutes() < 5) return;
        lastTick = Instant.now();
        try {
            priceTick();
            if (Duration.between(lastPulse, Instant.now()).getSeconds() >= 60) {
                snapshot.pulse = pulse();
                lastPulse = Instant.now();
            }
        } catch (Exception e) {
            log.debug("Tick failed: {}", e.getMessage());
        }
    }

    // ---- the full run --------------------------------------------------------------------

    public boolean refreshAsync() {
        if (running.get()) return false;
        pool.submit(() -> refresh(false));
        return true;
    }

    public void refresh(boolean withChains) {
        if (!running.compareAndSet(false, true)) return;
        long t0 = System.currentTimeMillis();
        try {
            Snapshot s = new Snapshot();
            s.zoyaConfigured = zoya.configured();
            s.filters = filterSummary();

            // 1. screen
            List<Candidate> screened;
            Map<String, Candidate> bySymbol = new LinkedHashMap<>();
            try {
                screened = tv.screen();
                s.screenSource = "TradingView";
            } catch (Exception e) {
                log.warn("TradingView screen failed ({}); using the fallback universe", e.getMessage());
                screened = fallback(props.getFallbackUniverse());
                s.screenSource = "Fallback list";
                s.screenNote = "TradingView could not be reached, so fundamentals were not checked. "
                        + "These are the names in screener.fallback-universe.";
            }
            s.screened = screened.size();
            Map<String, List<String>> fails = new HashMap<>();
            for (Candidate c : screened) {
                List<String> f = failures(c, "tradingview".equals(c.source()));
                fails.put(c.symbol(), f);
                bySymbol.putIfAbsent(c.symbol(), c);
            }
            List<Candidate> passed = screened.stream().filter(c -> fails.get(c.symbol()).isEmpty())
                    .sorted(Comparator.comparingDouble((Candidate c) -> -nz(c.marketCap())))
                    .limit(props.getMaxAnalyzed()).toList();
            s.passedFilters = (int) screened.stream().filter(c -> fails.get(c.symbol()).isEmpty()).count();

            // 2. watchlist
            List<String> wl = watchlist.all();
            List<String> missing = wl.stream().filter(t -> !bySymbol.containsKey(t)).toList();
            List<Candidate> wlCands = new ArrayList<>();
            if (!missing.isEmpty()) {
                try {
                    Map<String, Candidate> found = new HashMap<>();
                    for (Candidate c : tv.lookup(missing)) found.putIfAbsent(c.symbol(), c);
                    for (String t : missing) {
                        Candidate c = found.get(t);
                        wlCands.add(c != null ? c : fallback(List.of(t)).stream().findFirst().orElse(null));
                    }
                } catch (Exception e) {
                    wlCands.addAll(fallback(missing));
                }
            }
            for (Candidate c : wlCands) if (c != null) fails.putIfAbsent(c.symbol(), failures(c, "tradingview".equals(c.source())));

            LinkedHashMap<String, Candidate> universe = new LinkedHashMap<>();
            passed.forEach(c -> universe.put(c.symbol(), c));
            for (String t : wl) if (bySymbol.containsKey(t)) universe.putIfAbsent(t, bySymbol.get(t));
            wlCands.stream().filter(Objects::nonNull).forEach(c -> universe.putIfAbsent(c.symbol(), c));

            // 3. analyze in parallel
            Map<String, StockRow> previous = snapshot.rows.stream().collect(Collectors.toMap(r -> r.symbol, r -> r, (a, b) -> a));
            List<Future<StockRow>> futures = new ArrayList<>();
            ExecutorService workers = Executors.newFixedThreadPool(6);
            for (Candidate c : universe.values()) {
                futures.add(workers.submit(() -> analyzeOne(c, fails.getOrDefault(c.symbol(), List.of()), previous.get(c.symbol()))));
            }
            for (Future<StockRow> f : futures) {
                try { s.rows.add(f.get(90, TimeUnit.SECONDS)); } catch (Exception e) { log.debug("analysis: {}", e.getMessage()); }
            }
            workers.shutdown();
            zoya.persist();

            // 4. bid/ask if Yahoo's session is up
            Map<String, Quote> quotes = yahoo.batchQuotes(s.rows.stream().map(r -> r.symbol).toList());
            for (StockRow r : s.rows) {
                Quote q = quotes.get(r.symbol);
                if (q != null && q.hasBidAsk()) {
                    r.spreadBps = Num.round(q.spreadBps(), 1);
                    analyzer.applyQuote(r, q);
                }
            }

            // 5. chains
            // every name gets a chain once a day: new names on the screen run they first appear,
            // the rest at 8:00 AM ET (or now, if today's chain is still missing)
            List<String> all = s.rows.stream().map(r -> r.symbol).toList();
            refreshChains(withChains ? all : chainsDue(all));
            s.lastChainRun = lastChainRun;
            s.pulse = pulse();
            lastPulse = Instant.now();
            s.generatedAt = OffsetDateTime.now().toString();
            rebuild(s);
            snapshot = s;
            store.write(SNAPSHOT, s);
            log.info("Screen done: {} screened, {} passed, {} rows in {} ms", s.screened, s.passedFilters, s.rows.size(),
                    System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("Screen failed", e);
        } finally {
            running.set(false);
        }
    }

    private StockRow analyzeOne(Candidate c, List<String> fail, StockRow prev) {
        StockRow r = new StockRow();
        r.symbol = c.symbol();
        r.exchange = c.exchange();
        r.name = c.name();
        r.userAdded = watchlist.contains(c.symbol());
        r.source = c.source();
        r.screenFails = new ArrayList<>(fail);
        r.screenPass = fail.isEmpty();
        if (!"tradingview".equals(c.source())) r.screenFails.add("Fundamentals not verified (TradingView unreachable)");
        r.marketCap = Num.of(c.marketCap());
        r.targetUpsidePct = Num.round(c.targetUpsidePct(), 1);
        r.debtToEquity = Num.round(c.debtToEquity(), 3);
        r.debtToAssets = Num.round(c.debtToAssets(), 3);
        r.debtToCapital = Num.round(c.debtToCapital(), 3);
        r.cashToDebt = Num.round(c.cashToDebt(), 2);
        r.revenueGrowth = Num.round(c.revenueGrowth(), 1);
        ZoyaService.Entry z = zoya.get(c.symbol());
        r.zoya = z.status;
        r.zoyaReportDate = z.reportDate;
        r.zoyaCheckedAt = z.checkedAt;
        try {
            analyzer.analyze(c, r);
        } catch (Exception e) {
            r.error = e.getMessage();
            if (prev != null) { // keep yesterday's numbers rather than blank the row
                prev.error = "Stale: " + e.getMessage();
                prev.userAdded = r.userAdded;
                return prev;
            }
        }
        return r;
    }

    /** Re-derives the lists, ranks and GEX levels. Safe to call any time. */
    private void rebuild(Snapshot s) {
        for (StockRow r : s.rows) {
            r.gex = gexCache.get(r.symbol);
            r.userAdded = watchlist.contains(r.symbol);
        }
        // Core: every screened or added name with a trend to trade (not No Uptrend), sorted by the latest
        // session's phase (Bull Distribution, Absorption, Bull Manipulation, Bull Accumulation, then the rest),
        // then that phase's percent, then the long score
        for (StockRow r : s.rows) {
            LongScore.Result ls = LongScore.of(r);
            r.longScore = ls.score();
            r.longScoreParts = ls.parts();
            r.sessionGroup = sessionGroup(r.sessionPhase);
            r.list = r.userAdded ? "NO_UPTREND" : "SCREEN_ONLY";
            r.rank = 0;
        }
        List<StockRow> core = s.rows.stream()
                .filter(r -> (r.screenPass || r.userAdded) && !TrendClassifier.NONE.equals(r.trendClass))
                .sorted(Comparator.comparingInt((StockRow r) -> r.sessionGroup)
                        .thenComparingInt(r -> r.sessionPct == null ? 0 : -r.sessionPct)
                        .thenComparingInt(r -> -r.longScore)
                        .thenComparingDouble(r -> -Num.d(r.smaGapPct)))
                .toList();
        for (int i = 0; i < core.size(); i++) {
            core.get(i).list = "CORE";
            core.get(i).rank = i + 1;
        }
        s.compliant = (int) s.rows.stream().filter(r -> r.screenPass && "COMPLIANT".equals(r.zoya)).count();
        s.chainsAvailable = !gexCache.isEmpty();
    }

    /** Sort order of a session phase label such as "Bull Distribution". */
    static int sessionGroup(String phase) {
        if (phase == null) return 9;
        return switch (phase) {
            case "Bull Distribution" -> 1;
            case "Bull Absorption" -> 2;
            case "Bear Absorption" -> 3;
            case "Bull Manipulation" -> 4;
            case "Bull Accumulation" -> 5;
            default -> 9;
        };
    }

    List<String> failures(Candidate c, boolean haveFundamentals) {
        ScreenerProperties.Filters f = props.getFilters();
        List<String> out = new ArrayList<>();
        if (!(c.close() >= f.getPriceMin() && c.close() <= f.getPriceMax())) out.add("Price outside $" + f.getPriceMin() + "–$" + f.getPriceMax());
        if (!haveFundamentals) return out; // the row says the fundamentals were not verified
        if (c.exchange() != null && !f.getExchanges().contains(c.exchange())) out.add("Exchange " + c.exchange());
        if (!(c.marketCap() > f.getMarketCapMin())) out.add("Market cap");
        if (!(c.targetUpsidePct() > f.getTargetUpsideMin())) out.add("Target upside");
        if (!(c.debtToEquity() < f.getDebtEquityMax())) out.add("Debt/equity");
        if (!(c.debtToAssets() < f.getDebtAssetsMax())) out.add("Debt/assets");
        if (!(c.debtToCapital() < f.getDebtCapitalMax())) out.add("Debt/capital");
        if (!(c.cashToDebt() > f.getCashDebtMin())) out.add("Cash/debt");
        if (!(c.revenueGrowth() > f.getRevenueGrowthMin())) out.add("Revenue growth");
        return out;
    }

    private Map<String, Object> filterSummary() {
        ScreenerProperties.Filters f = props.getFilters();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exchanges", f.getExchanges());
        m.put("priceMin", f.getPriceMin());
        m.put("priceMax", f.getPriceMax());
        m.put("marketCapMin", f.getMarketCapMin());
        m.put("targetUpsideMin", f.getTargetUpsideMin());
        m.put("debtEquityMax", f.getDebtEquityMax());
        m.put("debtAssetsMax", f.getDebtAssetsMax());
        m.put("debtCapitalMax", f.getDebtCapitalMax());
        m.put("cashDebtMin", f.getCashDebtMin());
        m.put("revenueGrowthMin", f.getRevenueGrowthMin());
        return m;
    }

    /** Builds candidates from Yahoo's chart meta when TradingView is out of reach. */
    private List<Candidate> fallback(List<String> symbols) {
        List<Candidate> out = new ArrayList<>();
        for (String s : symbols) {
            try {
                var meta = yahoo.chart(s, "1d", "5d", false).meta();
                String exch = Http.text(meta, "fullExchangeName");
                exch = exch == null ? "" : exch.toUpperCase().startsWith("NASDAQ") ? "NASDAQ"
                        : exch.toUpperCase().startsWith("NYSE") ? "NYSE" : exch;
                String name = Http.text(meta, "longName");
                if (name == null) name = Http.text(meta, "shortName");
                double n = Double.NaN;
                out.add(new Candidate(s, exch, name == null ? s : name, Http.num(meta, "regularMarketPrice"), n, n, n, n, n,
                        n, n, n, n, n, n, n, n, n, null, "yahoo"));
            } catch (Exception e) {
                log.debug("Fallback lookup failed for {}: {}", s, e.getMessage());
            }
        }
        return out;
    }

    // ---- chains ----------------------------------------------------------------------------

    /** Names with no chain read today that have not been tried today either. */
    List<String> chainsDue(List<String> symbols) {
        LocalDate today = clock.now().toLocalDate();
        return symbols.stream().filter(sym -> {
            GexLevels g = gexCache.get(sym);
            return (g == null || !today.equals(g.asOf())) && !today.equals(chainTried.get(sym));
        }).toList();
    }

    public void refreshChains(List<String> symbols) {
        if (symbols.isEmpty()) return;
        int found = 0;
        LocalDate today = clock.now().toLocalDate();
        for (String s : symbols) {
            chainTried.put(s, today);
            List<OptionChain> chains = yahoo.optionChains(s, CHAIN_DAYS, CHAIN_MAX_EXPIRIES);
            if (chains.isEmpty()) continue;
            StockRow r = snapshot.rows.stream().filter(x -> x.symbol.equals(s)).findFirst().orElse(null);
            double spot = r != null && r.price != null ? r.price : chains.get(0).spot();
            GexLevels g = GexCalculator.compute(chains, spot, today);
            if (g != null) { gexCache.put(s, g); found++; }
        }
        lastChainRun = OffsetDateTime.now().toString();
        snapshot.lastChainRun = lastChainRun;
        store.write(GEX, gexCache);
        log.info("Chains: {} of {} names had open interest", found, symbols.size());
    }

    // ---- price tick ------------------------------------------------------------------------

    private void priceTick() {
        Snapshot s = snapshot;
        Set<String> symbols = new LinkedHashSet<>();
        s.rows.forEach(r -> symbols.add(r.symbol));
        symbols.addAll(alerts.activeSymbols());
        if (symbols.isEmpty()) return;

        Map<String, Quote> quotes = yahoo.batchQuotes(symbols);
        if (quotes.isEmpty()) { // no crumb: walk the list a dozen names per tick
            List<String> list = new ArrayList<>(symbols);
            for (int i = 0; i < Math.min(12, list.size()); i++) {
                String sym = list.get((roundRobin + i) % list.size());
                try { quotes.put(sym, yahoo.chartQuote(sym)); } catch (Exception ignored) { }
            }
            roundRobin = (roundRobin + 12) % Math.max(1, list.size());
        }
        for (StockRow r : s.rows) {
            Quote q = quotes.get(r.symbol);
            if (q != null) analyzer.applyQuote(r, q);
        }
        for (Quote q : quotes.values()) recentlyRang.addAll(alerts.check(q.symbol(), q.price(), q.previousClose()));
        while (recentlyRang.size() > 50) recentlyRang.remove(0);
        rebuild(s);
    }

    public List<Alert> recentAlerts() { return new ArrayList<>(recentlyRang); }

    // ---- pulse -----------------------------------------------------------------------------

    private MarketPulse pulse() {
        MarketPulse p = new MarketPulse();
        p.session = clock.sessionLabel();
        int below = 0, read = 0;
        for (String sym : INDEXES) {
            try {
                YahooClient.Chart ch = yahoo.chart(sym, "5m", "1d", false);
                MarketPulse.IndexRead ir = new MarketPulse.IndexRead();
                ir.symbol = sym;
                double price = Http.num(ch.meta(), "regularMarketPrice");
                double prev = Http.num(ch.meta(), "chartPreviousClose");
                ir.price = Num.round(price, 2);
                ir.vwap = Num.round(Indicators.vwap(ch.bars()), 2);
                ir.changePct = prev > 0 ? Num.round((price / prev - 1) * 100, 2) : null;
                ir.aboveVwap = ir.vwap != null && price > ir.vwap;
                if (!ir.aboveVwap) below++;
                read++;
                p.indexes.add(ir);
            } catch (Exception e) {
                log.debug("Pulse {} failed: {}", sym, e.getMessage());
            }
        }
        if (read > 0) p.regime = below * 2 > read ? "Bearish" : "Bullish";
        try {
            int[] nq = tv.breadth("NASDAQ");
            int[] ny = tv.breadth("NYSE");
            p.nasdaqUp = nq[0]; p.nasdaqDown = nq[1];
            p.nyseUp = ny[0]; p.nyseDown = ny[1];
        } catch (Exception e) {
            log.debug("Breadth failed: {}", e.getMessage());
        }
        return p;
    }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }
}
