package com.personalscreener.web;

import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.Alert;
import com.personalscreener.model.Snapshot;
import com.personalscreener.model.StockRow;
import com.personalscreener.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ScreenService screen;
    private final WatchlistService watchlist;
    private final AlertService alerts;
    private final AnalyzeService analyze;
    private final TechnicalsService technicals;
    private final BacktestService backtest;
    private final SessionService sessions;
    private volatile Map<String, Object> lastBacktest;
    private final MarketClock clock;
    private final ScreenerProperties props;
    private final ZoyaService zoya;
    private final PvService pv;
    private final BiasService bias;
    private final SignalAlertService signalAlerts;
    private final PlanService plan;
    private final PlaybookService playbook;

    public ApiController(ScreenService screen, WatchlistService watchlist, AlertService alerts, AnalyzeService analyze,
                         TechnicalsService technicals, BacktestService backtest, SessionService sessions, MarketClock clock,
                         ScreenerProperties props, ZoyaService zoya, PvService pv, BiasService bias,
                         SignalAlertService signalAlerts, PlanService plan, PlaybookService playbook) {
        this.playbook = playbook;
        this.plan = plan;
        this.signalAlerts = signalAlerts;
        this.bias = bias;
        this.zoya = zoya;
        this.pv = pv;
        this.sessions = sessions;
        this.backtest = backtest;
        this.screen = screen;
        this.watchlist = watchlist;
        this.alerts = alerts;
        this.analyze = analyze;
        this.technicals = technicals;
        this.clock = clock;
        this.props = props;
    }

    /** The full screen. */
    @GetMapping("/screen")
    public Map<String, Object> screen() {
        Snapshot s = screen.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshot", s);
        m.put("running", screen.isRunning());
        m.put("now", clock.now().toOffsetDateTime().toString());
        m.put("session", clock.sessionLabel());
        m.put("inRefreshWindow", clock.inRefreshWindow());
        return m;
    }

    /** Light payload for the 10-second price poll. */
    @GetMapping("/prices")
    public Map<String, Object> prices() {
        Snapshot s = screen.snapshot();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockRow r : s.rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", r.symbol);
            p.put("price", r.price);
            p.put("changePct", r.changePct);
            p.put("dayRange", r.dayRange);
            p.put("atr", r.atr);
            p.put("rangeUsedPct", r.rangeUsedPct);
            p.put("rangeLeft", r.rangeLeft);
            p.put("relVol", r.relVol);
            p.put("relVol30", r.relVol30);
            p.put("state", r.state);
            p.put("toBreakoutPct", r.toBreakoutPct);
            p.put("qualityScore", r.qualityScore);
            p.put("qualityPass", r.qualityPass);
            p.put("spreadBps", r.spreadBps);
            p.put("list", r.list);
            rows.add(p);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", clock.now().toOffsetDateTime().toString());
        m.put("session", clock.sessionLabel());
        m.put("pulse", s.pulse);
        m.put("rows", rows);
        m.put("alerts", screen.recentAlerts());
        m.put("pvAlerts", pv.recentAlerts());
        m.put("signalAlerts", signalAlerts.recent());
        m.put("planAlerts", plan.recent());
        var todayBias = bias.today(clock.now().toLocalDate());
        if (todayBias != null) {
            var last = todayBias.intraday.isEmpty() ? null : todayBias.intraday.get(todayBias.intraday.size() - 1);
            m.put("bias", last == null
                    ? Map.of("id", todayBias.date, "title", BiasService.title(todayBias), "summary", todayBias.summary)
                    : Map.of("id", todayBias.date + " " + last.time, "title",
                            BiasService.intradayTitle(last, clock.now().toLocalDate()), "summary", last.summary));
        }
        m.put("generatedAt", s.generatedAt);
        m.put("running", screen.isRunning());
        return m;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return Map.of("started", screen.refreshAsync());
    }

    // ---- watchlist ------------------------------------------------------------------------

    @GetMapping("/watchlist")
    public List<String> watchlist() { return watchlist.all(); }

    @PostMapping("/watchlist")
    public Map<String, Object> add(@RequestBody Map<String, String> body) {
        List<String> added = watchlist.add(body.getOrDefault("tickers", ""));
        if (!added.isEmpty()) screen.refreshAsync();
        return Map.of("added", added, "watchlist", watchlist.all());
    }

    @DeleteMapping("/watchlist/{ticker}")
    public Map<String, Object> remove(@PathVariable String ticker) {
        boolean removed = watchlist.remove(ticker);
        if (removed) screen.snapshot().rows.removeIf(r -> r.symbol.equalsIgnoreCase(ticker) && !r.screenPass);
        return Map.of("removed", removed, "watchlist", watchlist.all());
    }

    @GetMapping(value = "/watchlist/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"watchlist.txt\"")
                .body(String.join("\n", watchlist.all()) + "\n");
    }

    // ---- alerts ---------------------------------------------------------------------------

    @GetMapping("/alerts")
    public List<Alert> alerts() { return alerts.all(); }

    @PostMapping("/alerts")
    public Alert createAlert(@RequestBody Map<String, Object> body) {
        double level = Double.parseDouble(String.valueOf(body.getOrDefault("level", "0")));
        return alerts.create(String.valueOf(body.get("symbol")), String.valueOf(body.get("type")), level,
                body.get("note") == null ? "" : String.valueOf(body.get("note")));
    }

    @DeleteMapping("/alerts/{id}")
    public Map<String, Object> deleteAlert(@PathVariable String id) { return Map.of("deleted", alerts.delete(id)); }

    // ---- analyze / technicals ----------------------------------------------------------------

    @GetMapping("/analyze/{ticker}")
    public Map<String, Object> analyze(@PathVariable String ticker) throws Exception {
        return analyze.analyze(clean(ticker));
    }

    @GetMapping("/technicals/{ticker}")
    public Map<String, Object> technicals(@PathVariable String ticker) throws Exception {
        return technicals.technicals(clean(ticker));
    }

    /** Quantum Shift backtest, long only. Defaults: every name in the app, last 60 days, 1-hour bars, energy 60. */
    @GetMapping("/backtest")
    public Map<String, Object> backtest(@RequestParam(required = false) String tickers,
                                        @RequestParam(defaultValue = "60") int days,
                                        @RequestParam(defaultValue = "60m") String interval,
                                        @RequestParam(defaultValue = "60") double threshold,
                                        @RequestParam(defaultValue = "2.0") double stopAtr,
                                        @RequestParam(defaultValue = "2.5") double trailAtr,
                                        @RequestParam(defaultValue = "35") int maxBars,
                                        @RequestParam(defaultValue = "5") double costBps) throws Exception {
        if (!List.of("15m", "60m", "1d").contains(interval)) throw new IllegalArgumentException("interval must be 15m, 60m or 1d");
        if (days < 5 || days > 365) throw new IllegalArgumentException("days must be 5 to 365");
        var p = new BacktestService.Params(days, interval, threshold, false, stopAtr, trailAtr, maxBars, costBps);
        lastBacktest = backtest.run(WatchlistService.parse(tickers), p);
        return lastBacktest;
    }

    @GetMapping("/backtest/last")
    public Map<String, Object> lastBacktest() {
        return lastBacktest == null ? Map.of() : lastBacktest;
    }

    @GetMapping("/backtest/universe")
    public List<String> backtestUniverse() { return backtest.universe(); }

    /** Asia / London / New York session phases plus candles for the chosen timeframe. */
    @GetMapping("/sessions/{ticker}")
    public Map<String, Object> sessions(@PathVariable String ticker, @RequestParam(defaultValue = "15m") String tf,
                                        @RequestParam(defaultValue = "3") int days) throws Exception {
        if (days < 1 || days > 15) throw new IllegalArgumentException("days must be 1 to 15");
        return sessions.read(symbol(ticker), tf, days);
    }

    /** Zoya compliance for one ticker: from the day-long cache, or looked up now when it is missing or stale. */
    @GetMapping("/zoya/{ticker}")
    public ZoyaService.Entry zoya(@PathVariable String ticker, @RequestParam(defaultValue = "false") boolean refresh) {
        return zoya.get(symbol(ticker), refresh);
    }

    /** Every cached Zoya result. */
    @GetMapping("/zoya")
    public Map<String, ZoyaService.Entry> zoyaCache() { return new java.util.TreeMap<>(zoya.all()); }

    // ---- option chains -------------------------------------------------------------------------

    /** Why option chains are or are not loading: the Yahoo session, the last error and the last run's counts. */
    @GetMapping("/chains/status")
    public Map<String, Object> chainStatus() { return screen.chainStatus(); }

    /** One stock's option chain for the Screen page. {@code expiry} is ALL (the 45-day window) or a date. */
    @GetMapping("/chain/{ticker}")
    public Map<String, Object> chain(@PathVariable String ticker, @RequestParam(defaultValue = "ALL") String expiry) {
        return screen.chainView(symbol(ticker), expiry);
    }

    /** Reads every name's option chain again now. */
    @PostMapping("/chains/refresh")
    public Map<String, Object> chainRefresh() {
        screen.refreshChainsNow();
        return Map.of("started", true);
    }

    // ---- per-stock playbook --------------------------------------------------------------------

    /**
     * One stock's playbook: every strategy backtested on its own history, what each says now, and a plan from the
     * best active strategy with an edge on it. {@code risk}: dollars you will risk on the trade (for the share count).
     */
    @GetMapping("/playbook/{ticker}")
    public Map<String, Object> playbook(@PathVariable String ticker, @RequestParam(required = false) Double risk) throws Exception {
        return playbook.build(symbol(ticker), risk);
    }

    /** The trader profile: account size, risk % per trade and minimum R:R (used by the playbook). */
    @GetMapping("/profile")
    public PlaybookService.Profile profile() { return playbook.profile(); }

    @PutMapping("/profile")
    public PlaybookService.Profile saveProfile(@RequestBody PlaybookService.Profile p) { return playbook.saveProfile(p); }

    // ---- trading plan bot ----------------------------------------------------------------------

    /** The plan bot: open plan trades, today's BUY NOW / SELL NOW, the watch list, the backtest and the settings. */
    @GetMapping("/plan")
    public Map<String, Object> plan() { return plan.view(); }

    @PostMapping("/plan/scan")
    public Map<String, Object> planScan() { return Map.of("started", plan.scanAsync()); }

    @PostMapping("/plan/backtest")
    public Map<String, Object> planBacktest() { return Map.of("started", plan.backtestAsync()); }

    @GetMapping("/plan/alerts")
    public List<PlanService.PlanAlert> planAlerts() { return plan.allAlerts(); }

    @PutMapping("/plan/settings")
    public com.personalscreener.analysis.TradePlan.Settings planSettings(@RequestBody com.personalscreener.analysis.TradePlan.Settings s) {
        return plan.saveSettings(s);
    }

    // ---- market bias ---------------------------------------------------------------------------

    /** Today's 9:30 call (or a preview before then), the history with outcomes, accuracy and phone channels. */
    @GetMapping("/bias")
    public Map<String, Object> bias(@RequestParam(defaultValue = "false") boolean refresh) {
        Map<String, Object> m = new LinkedHashMap<>();
        var today = bias.today(clock.now().toLocalDate());
        m.put("today", today);
        m.put("preview", today == null || refresh ? bias.preview(refresh) : null);
        var now = clock.now();
        boolean inSession = clock.isTradingDay(now.toLocalDate()) && !now.toLocalTime().isBefore(java.time.LocalTime.of(10, 0))
                && now.toLocalTime().isBefore(java.time.LocalTime.of(16, 5));
        if (refresh && inSession) m.put("intradayNow", bias.computeIntraday(now));
        m.put("history", bias.history());
        m.put("accuracy", bias.accuracy());
        m.put("channels", bias.channels());
        m.put("callTime", "9:30 AM ET, trading days");
        return m;
    }

    /** Sends a test message to the phone channels. */
    @PostMapping("/bias/test")
    public Map<String, Object> biasTest() { return Map.of("result", bias.testAlert()); }

    // ---- PV Confirm ---------------------------------------------------------------------------

    /** PV Confirm rows (uptrend and downtrend sections), SPY's state and the settings. */
    @GetMapping("/pv")
    public Map<String, Object> pv() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", pv.state());
        m.put("settings", pv.settings());
        m.put("running", pv.isRunning());
        return m;
    }

    @PostMapping("/pv/refresh")
    public Map<String, Object> pvRefresh() { return Map.of("started", pv.refreshAsync()); }

    @GetMapping("/pv/settings")
    public com.personalscreener.analysis.PvConfirm.Settings pvSettings() { return pv.settings(); }

    @PutMapping("/pv/settings")
    public com.personalscreener.analysis.PvConfirm.Settings pvSaveSettings(@RequestBody com.personalscreener.analysis.PvConfirm.Settings s) {
        var saved = pv.saveSettings(s);
        pv.refreshAsync();
        return saved;
    }

    /** BUY NOW and SELL NOW alerts for the Core list, newest first. */
    @GetMapping("/signal-alerts")
    public List<SignalAlertService.SignalAlert> signalAlerts() { return signalAlerts.all(); }

    @GetMapping("/pv/alerts")
    public List<PvService.PvAlert> pvAlerts() { return pv.alerts(); }

    /** The magnet zone (volume profile POC band) for a symbol, and how often price really touched it. */
    @GetMapping("/magnet/{ticker}")
    public Map<String, Object> magnet(@PathVariable String ticker, @RequestParam(defaultValue = "20") int days) throws Exception {
        if (days < 5 || days > 40) throw new IllegalArgumentException("days must be 5 to 40");
        return sessions.magnet(symbol(ticker), days);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filters", props.getFilters());
        m.put("quality", props.getQuality());
        m.put("zoyaConfigured", zoya.configured());
        m.put("zoyaSource", zoya.source());
        m.put("maxAnalyzed", props.getMaxAnalyzed());
        return m;
    }

    /** Tickers plus Yahoo's futures, FX and crypto forms: ES=F, EURUSD=X, BTC-USD, ^GSPC. */
    private static String symbol(String t) {
        String s = t == null ? "" : t.trim().toUpperCase(Locale.ROOT);
        if (!s.matches("^\\^?[A-Z0-9.\\-]{1,12}(=[FX])?$")) throw new IllegalArgumentException("Not a symbol: " + t);
        return s;
    }

    private static String clean(String t) {
        List<String> p = WatchlistService.parse(t);
        if (p.size() != 1) throw new IllegalArgumentException("Not a ticker: " + t);
        return p.get(0);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        org.slf4j.LoggerFactory.getLogger(ApiController.class).warn("Request failed", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
