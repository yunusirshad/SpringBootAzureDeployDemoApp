package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.analysis.GexCalculator;
import com.personalscreener.analysis.LongScore;
import com.personalscreener.analysis.SetupScore;
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
    private final CboeClient cboe;
    private final ProfileService profiles;
    private final SignalAlertService signalAlerts;
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
    private volatile String chainNote;
    private volatile String lastChainSource;
    private volatile Map<String, Object> chainStats = Map.of();
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
                         MarketClock clock, JsonStore store, CboeClient cboe, SignalAlertService signalAlerts,
                         ProfileService profiles) {
        this.profiles = profiles;
        this.props = props;
        this.signalAlerts = signalAlerts;
        this.cboe = cboe;
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

    /** Full screen every 15 minutes on trading days: 7:00, 7:15, ... 4:00 PM ET. */
    @Scheduled(cron = "0 */15 * * * *", zone = "America/New_York")
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
        // Core: screened or added names that are Zoya compliant and in a Strong Uptrend or Early Bullish Reversal.
        // Long only. Buy now, Ready to buy, Sell now (exit the long), Ready to sell, Hold, Wait; each group by the
        // setup score (range meter, reward:risk, session phase, long score). The best Buy now (else Ready to buy)
        // name is the super stock, on top.
        for (StockRow r : s.rows) {
            LongScore.Result ls = LongScore.of(r);
            r.longScore = ls.score();
            r.longScoreParts = ls.parts();
            r.sessionGroup = sessionGroup(r.sessionPhase);
            var pr = profiles.get();
            r.purple = com.personalscreener.analysis.PurpleZone.classify(Num.d(r.price), Num.d(r.atr), Num.d(r.previousClose),
                    r.purpleZones, com.personalscreener.analysis.PurpleZone.DEFAULT_MIN_SCORE, pr.account, pr.riskDollars(), pr.minRR);
            SetupScore.Result su = SetupScore.of(r);
            r.setupScore = su.score();
            r.setupParts = su.parts();
            r.rewardRisk = su.rewardRisk();
            r.setupStop = su.stop();
            r.setupTarget = su.target();
            r.riskPct = su.riskPct();
            r.rewardPct = su.rewardPct();
            r.superStock = false;
            SetupScore.Signal sig = SetupScore.signal(r);
            r.signal = sig.signal();
            r.signalNotes = sig.notes();
            double[] ex = SetupScore.WAIT.equals(r.signal) ? SetupScore.expected(r) : null;
            r.expBuy = ex == null ? null : ex[0];
            r.expStop = ex == null ? null : ex[1];
            r.expTarget = ex == null ? null : ex[2];
            r.list = r.userAdded ? "NO_UPTREND" : "SCREEN_ONLY";
            r.rank = 0;
        }
        List<StockRow> core = s.rows.stream()
                .filter(r -> (r.screenPass || r.userAdded) && inCore(r))
                .sorted(Comparator.comparingInt((StockRow r) -> signalOrder(r.signal))
                        .thenComparingInt(r -> -r.setupScore)
                        .thenComparingInt(r -> -r.longScore)
                        .thenComparingDouble(r -> -Num.d(r.smaGapPct)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        core.stream().filter(r -> SetupScore.BUY_NOW.equals(r.signal) || SetupScore.READY.equals(r.signal))
                .findFirst().ifPresent(best -> {
            best.superStock = true;
            core.remove(best);
            core.add(0, best);
        });
        for (int i = 0; i < core.size(); i++) {
            core.get(i).list = "CORE";
            core.get(i).rank = i + 1;
        }
        for (StockRow r : s.rows) {
            r.offReason = null;
            if (!r.userAdded || "CORE".equals(r.list)) continue;
            if (!"COMPLIANT".equals(r.zoya)) r.offReason = switch (r.zoya == null ? "" : r.zoya) {
                case "NON_COMPLIANT" -> "Not Shariah-compliant on Zoya, so it is not listed";
                case "QUESTIONABLE" -> "Questionable on Zoya, so it is not listed";
                case "NOT_COVERED" -> "Zoya does not cover it, so it is not listed";
                default -> "Zoya compliance not confirmed yet, so it is not listed";
            };
            else r.offReason = TrendClassifier.BEAR_REV.equals(r.trendClass)
                    ? "Early Bearish Reversal: not a Strong Uptrend or Early Bullish Reversal, so it stays off Core"
                    : "No uptrend, so it stays off Core";
        }
        signalAlerts.check(core);
        s.compliant = (int) s.rows.stream().filter(r -> r.screenPass && "COMPLIANT".equals(r.zoya)).count();
        s.chainsAvailable = !gexCache.isEmpty();
        s.chainNote = chainNote;
    }

    /** Core holds compliant names in a Strong Uptrend or Early Bullish Reversal only. */
    static boolean inCore(StockRow r) {
        return "COMPLIANT".equals(r.zoya) && SetupScore.longTrend(r);
    }

    static int signalOrder(String signal) {
        return switch (signal == null ? "" : signal) {
            case SetupScore.BUY_NOW -> 0;
            case SetupScore.READY -> 1;
            case SetupScore.READY_SELL -> 2;
            case SetupScore.SELL_NOW -> 3;
            case SetupScore.HOLD -> 4;
            default -> 5;
        };
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

    /** Cboe first (unless screener.options.source is "yahoo"), then Yahoo when Cboe did not answer. */
    private YahooClient.ChainResult chains(String symbol, LocalDate today) {
        if (!"yahoo".equalsIgnoreCase(props.getOptions().getSource())) {
            YahooClient.ChainResult c = cboe.chainResult(symbol, CHAIN_DAYS, CHAIN_MAX_EXPIRIES, today);
            if (c.answered()) { lastChainSource = "Cboe"; return c; }
        }
        YahooClient.ChainResult y = yahoo.chainResult(symbol, CHAIN_DAYS, CHAIN_MAX_EXPIRIES);
        if (y.answered()) lastChainSource = "Yahoo";
        return y;
    }

    private String chainError() {
        List<String> e = new ArrayList<>();
        if (!"yahoo".equalsIgnoreCase(props.getOptions().getSource()) && cboe.lastError() != null) e.add(cboe.lastError());
        if (yahoo.lastError() != null) e.add(yahoo.lastError());
        return String.join(" · ", e);
    }

    private final Map<String, Map.Entry<Instant, YahooClient.ChainResult>> chainViewCache = new ConcurrentHashMap<>();

    /**
     * The option chain for the Screen page: strikes within 40% of the price with call and put open interest, IV and
     * net gamma, summed over every expiry in the window ({@code expiry} = ALL) or for one expiry, plus the walls
     * and King for that selection. Kept for 15 minutes.
     */
    public Map<String, Object> chainView(String symbol, String expiry) {
        LocalDate today = clock.now().toLocalDate();
        var cached = chainViewCache.get(symbol);
        YahooClient.ChainResult cr;
        if (cached != null && cached.getKey().isAfter(Instant.now().minus(Duration.ofMinutes(15)))) cr = cached.getValue();
        else {
            cr = chains(symbol, today);
            if (cr.answered()) chainViewCache.put(symbol, Map.entry(Instant.now(), cr));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("source", lastChainSource);
        m.put("readAt", cached != null && cr == cached.getValue() ? cached.getKey().toString() : Instant.now().toString());
        List<OptionChain> all = cr.chains();
        if (all.isEmpty()) {
            m.put("error", cr.answered() ? symbol + " has no listed options" : "Could not read the chain: " + chainError());
            return m;
        }
        StockRow row = snapshot.rows.stream().filter(x -> x.symbol.equals(symbol)).findFirst().orElse(null);
        double spot = row != null && row.price != null ? row.price : all.get(0).spot();
        List<OptionChain> sel = expiry == null || expiry.isBlank() || expiry.equalsIgnoreCase("ALL") ? all
                : all.stream().filter(c -> c.expiry().toString().equals(expiry)).toList();
        if (sel.isEmpty()) sel = all;
        m.put("spot", spot);
        m.put("expiries", all.stream().map(c -> Map.of("date", c.expiry().toString(),
                "oi", c.calls().stream().mapToDouble(OptionChain.Strike::openInterest).sum()
                        + c.puts().stream().mapToDouble(OptionChain.Strike::openInterest).sum())).toList());
        m.put("selected", sel.size() == all.size() ? "ALL" : sel.get(0).expiry().toString());
        m.put("levels", GexCalculator.compute(sel, spot, today));
        double lo = spot * 0.6, hi = spot * 1.4;
        m.put("strikes", GexCalculator.byStrike(sel, spot, today).stream()
                .filter(k -> k.strike() >= lo && k.strike() <= hi && (k.callOi() > 0 || k.putOi() > 0)).toList());
        return m;
    }

    /** For GET /api/chains/status. */
    public Map<String, Object> chainStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", props.getOptions().getSource());
        m.put("lastSourceUsed", lastChainSource);
        m.put("cboeUrl", props.getOptions().getCboeUrl() + "{SYMBOL}.json");
        m.put("cboeLastError", cboe.lastError());
        m.put("yahoo", yahoo.sessionStatus());
        m.put("lastRun", chainStats);
        m.put("cached", gexCache.size());
        m.put("cachedToday", gexCache.values().stream().filter(g -> clock.now().toLocalDate().equals(g.asOf())).count());
        m.put("note", chainNote);
        return m;
    }

    /** Reads every listed name's chain again now. */
    public void refreshChainsNow() {
        pool.submit(() -> {
            refreshChains(snapshot.rows.stream().map(r -> r.symbol).toList());
            rebuild(snapshot);
            store.write(SNAPSHOT, snapshot);
        });
    }

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
        int failed = 0, none = 0;
        for (String s : symbols) {
            YahooClient.ChainResult cr = chains(s, today);
            // only an answer from Yahoo counts as tried; a failed session is retried on the next screen
            if (cr.answered()) chainTried.put(s, today); else failed++;
            List<OptionChain> chains = cr.chains();
            if (chains.isEmpty()) { if (cr.answered()) none++; continue; }
            StockRow r = snapshot.rows.stream().filter(x -> x.symbol.equals(s)).findFirst().orElse(null);
            double spot = r != null && r.price != null ? r.price : chains.get(0).spot();
            GexLevels g = GexCalculator.compute(chains, spot, today);
            if (g != null) { gexCache.put(s, g); found++; }
        }
        lastChainRun = OffsetDateTime.now().toString();
        snapshot.lastChainRun = lastChainRun;
        store.write(GEX, gexCache);
        chainNote = failed == 0 ? null : "Option chains: " + failed + " of " + symbols.size()
                + " names could not be read. " + chainError()
                + " Names without a chain show \"No chain\"; GET /api/chains/status has the details.";
        chainStats = Map.of("asked", symbols.size(), "withOpenInterest", found, "noOptions", none, "failed", failed,
                "at", OffsetDateTime.now().toString());
        if (failed > 0) log.warn("Chains: {} of {} failed: {}", failed, symbols.size(), chainError());
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
