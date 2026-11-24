package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.analysis.TradePlan;
import com.personalscreener.data.Bar;
import com.personalscreener.data.YahooClient;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.StockRow;
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

/**
 * The trading-plan bot. Every 15 minutes from 9:45 AM to 3:45 PM ET on trading days it scans every Zoya-compliant
 * name on the screener with {@link TradePlan}, and alerts:
 * <ul>
 *   <li><b>BUY NOW</b> with entry, stop, target and the grade's backtested win rate, but only for setups whose grade
 *       has won at least {@code minWinRate}% over at least {@code minTrades} trades in the 60-day backtest;</li>
 *   <li><b>SELL NOW</b> for an open plan trade when the stop or the target is hit, when the 1h trend fails while the
 *       trade is under water (close below the 1h 20 EMA), or at 3:45 PM (it is a day trade).</li>
 * </ul>
 * At most one plan trade per stock per day. Alerts go to the page and, when set up, to Telegram / ntfy.
 * The backtest runs after startup and at 6:30 PM ET on trading days.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final String SETTINGS = "plan-settings.json", OPEN = "plan-open.json", ALERTS = "plan-alerts.json",
            BACKTEST = "plan-backtest.json";
    static final LocalTime FIRST_SCAN = LocalTime.of(9, 45), LAST_ENTRY = LocalTime.of(15, 30), EOD_EXIT = LocalTime.of(15, 45);

    public static class OpenTrade {
        public String symbol, name, type, grade, date, entryAt, targetSource;
        public double entry, stop, target, rr;
        /** The planned risk per share (entry - first stop); the stop moves to the entry once price is up this much. */
        public double risk;
        public boolean breakeven;
        public Double winRate, last;
        public List<String> confluence = List.of();
    }

    public static class PlanAlert {
        public String id, symbol, action, at, reason, grade, type;
        public Double price, entry, stop, target, rr, winRate, resultPct;
        public List<String> sent = List.of();
    }

    public static class Watch {
        public String symbol, note;
        public Double price;
    }

    public static class Backtest {
        public String generatedAt, from, to;
        public int stocks;
        public Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        public List<TradePlan.Trade> recentTrades = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
    }

    private final YahooClient yahoo;
    private final ScreenService screen;
    private final JsonStore store;
    private final MarketClock clock;
    private final Notifier notifier;
    private volatile TradePlan.Settings settings;
    private final Map<String, OpenTrade> open;
    private final List<PlanAlert> alerts;
    private volatile Backtest backtest;
    private volatile List<Watch> watching = List.of();
    private volatile String lastScan, lastScanNote;
    private volatile int lastScanned;
    private final AtomicBoolean scanning = new AtomicBoolean(false), testing = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Map.Entry<Instant, List<Bar>>> slowCache = new ConcurrentHashMap<>();

    public PlanService(YahooClient yahoo, ScreenService screen, JsonStore store, MarketClock clock, Notifier notifier) {
        this.yahoo = yahoo;
        this.screen = screen;
        this.store = store;
        this.clock = clock;
        this.notifier = notifier;
        this.settings = store.read(SETTINGS, new TypeReference<TradePlan.Settings>() {}, new TradePlan.Settings());
        this.open = new ConcurrentHashMap<>(store.read(OPEN, new TypeReference<Map<String, OpenTrade>>() {}, new HashMap<>()));
        this.alerts = new CopyOnWriteArrayList<>(store.read(ALERTS, new TypeReference<List<PlanAlert>>() {}, new ArrayList<>()));
        this.backtest = store.read(BACKTEST, new TypeReference<Backtest>() {}, null);
    }

    // ---- schedule ------------------------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        pool.submit(() -> {
            for (int i = 0; i < 120 && screen.snapshot().rows.isEmpty(); i++) {
                try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            }
            boolean stale = backtest == null || backtest.generatedAt == null
                    || OffsetDateTime.parse(backtest.generatedAt).isBefore(OffsetDateTime.now().minusHours(20));
            if (stale) runBacktest();
            scan();
        });
    }

    /** Every 15 minutes (a minute after the 5m and 15m candles close), 9:46 AM to 3:46 PM ET. */
    @Scheduled(cron = "0 1,16,31,46 9-15 * * MON-FRI", zone = "America/New_York")
    public void every15() {
        ZonedDateTime now = clock.now();
        if (clock.isTradingDay(now.toLocalDate()) && !now.toLocalTime().isBefore(FIRST_SCAN)) scan();
    }

    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "America/New_York")
    public void nightly() {
        if (clock.isTradingDay(clock.now().toLocalDate())) runBacktest();
    }

    // ---- scan ------------------------------------------------------------------------------------------

    public boolean scanAsync() {
        if (scanning.get()) return false;
        pool.submit(this::scan);
        return true;
    }

    public void scan() {
        if (!scanning.compareAndSet(false, true)) return;
        try {
            doScan();
        } catch (Exception e) {
            log.warn("Plan scan failed: {}", e.getMessage());
        } finally {
            scanning.set(false);
        }
    }

    private void doScan() throws Exception {
        TradePlan.Settings s = settings;
        ZonedDateTime now = clock.now();
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        boolean session = clock.isTradingDay(today) && !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(16, 0));
        long t = now.toEpochSecond();

        // positions from an earlier day are closed at that day's end (it is a day trade)
        for (OpenTrade o : List.copyOf(open.values())) {
            if (!o.date.equals(today.toString())) { open.remove(o.symbol); }
        }

        Map<String, StockRow> names = new LinkedHashMap<>();
        for (StockRow r : screen.snapshot().rows) {
            if ((r.screenPass || r.userAdded) && "COMPLIANT".equals(r.zoya)) names.put(r.symbol, r);
        }
        Set<String> symbols = new LinkedHashSet<>(names.keySet());
        symbols.addAll(open.keySet());
        List<Bar> spy15 = yahoo.bars("SPY", "15m", "5d");

        Map<String, Future<Object[]>> fs = new LinkedHashMap<>();
        for (String sym : symbols) {
            fs.put(sym, pool.submit(() -> {
                TradePlan.Bars b = new TradePlan.Bars(slow(sym, "1d", "2y"), slow(sym, "60m", "3mo"),
                        yahoo.bars(sym, "15m", "10d"), yahoo.bars(sym, "5m", "5d"), spy15);
                StockRow row = names.get(sym);
                double callWall = row != null && row.gex != null ? row.gex.callWall() : Double.NaN;
                return new Object[]{b, TradePlan.evaluate(b, t, s, callWall)};
            }));
        }
        List<Watch> watch = new ArrayList<>();
        int scanned = 0;
        for (var e : fs.entrySet()) {
            String sym = e.getKey();
            Object[] res;
            try { res = e.getValue().get(60, TimeUnit.SECONDS); } catch (Exception ex) { continue; }
            scanned++;
            TradePlan.Bars b = (TradePlan.Bars) res[0];
            TradePlan.Look look = (TradePlan.Look) res[1];
            List<Bar> m5 = b.m5();
            double last = m5.isEmpty() ? Double.NaN : m5.get(m5.size() - 1).close();

            OpenTrade o = open.get(sym);
            if (o != null) {
                o.last = last;
                if (session) manage(o, b, now);
                continue;
            }
            if (look.setup() != null) {
                TradePlan.Setup st = look.setup();
                Double wr = winRate(st);
                boolean passes = wr != null && wr >= s.minWinRate && trades(st) >= s.minTrades;
                boolean fresh = t - (st.signalTime() + 300) <= 16 * 60;   // confirmed in the last 15 minutes
                boolean traded = alerts.stream().anyMatch(a -> a.symbol.equals(sym) && a.action.equals("BUY NOW") && a.at.startsWith(today.toString()));
                if (session && time.isBefore(LAST_ENTRY.plusMinutes(1)) && fresh && passes && !traded) {
                    buy(sym, names.get(sym), st, wr, last);
                } else {
                    Watch w = new Watch();
                    w.symbol = sym;
                    w.price = last;
                    w.note = String.format("%s grade %s setup (entry %.2f, stop %.2f, target %.2f)", st.type().toLowerCase(), st.grade(), st.entry(), st.stop(), st.target())
                            + (passes ? "" : wr == null ? ", grade not yet backtested" : String.format(", grade wins %.0f%% (under %.0f%%)", wr, s.minWinRate))
                            + (traded ? ", already traded today" : "");
                    watch.add(w);
                }
            } else if (look.watch() != null) {
                Watch w = new Watch();
                w.symbol = sym;
                w.price = last;
                w.note = look.watch() + (look.reason() != null && !look.watch().contains("waiting") ? ": " + look.reason() : "");
                watch.add(w);
            }
        }
        watching = watch;
        lastScanned = scanned;
        lastScan = OffsetDateTime.now().toString();
        lastScanNote = session ? null : "Market closed: the bot scans from 9:45 AM to 3:45 PM ET on trading days. This scan is for reference only.";
        store.write(OPEN, open);
        log.info("Plan scan: {} names, {} open, {} watching", scanned, open.size(), watch.size());
    }

    /** Stop, target, failed 1h trend while under water, or 3:45 PM. */
    private void manage(OpenTrade o, TradePlan.Bars b, ZonedDateTime now) {
        long entryEpoch = OffsetDateTime.parse(o.entryAt).toEpochSecond();
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (Bar x : b.m5()) {
            if (x.time() + 300 <= entryEpoch) continue;
            low = Math.min(low, x.low());
            high = Math.max(high, x.high());
        }
        String why = null;
        double exit = o.last == null ? o.entry : o.last;
        if (low <= o.stop) { why = o.breakeven ? "Breakeven stop hit (it had reached +1R)" : "STOP hit"; exit = o.stop; }
        else if (high >= o.target) { why = "TARGET hit"; exit = o.target; }
        else if (!now.toLocalTime().isBefore(EOD_EXIT)) why = "End of day (3:45 PM): close the day trade";
        else {
            List<Bar> h1 = b.h1().stream().filter(x -> x.time() + 3600 <= now.toEpochSecond()).toList();
            if (h1.size() > 21) {
                double[] c = com.personalscreener.analysis.Indicators.closes(h1);
                if (c[c.length - 1] < com.personalscreener.analysis.Indicators.ema(c, 20) && exit < o.entry) why = "1h trend failed while under water";
            }
        }
        if (why == null) {
            // management: once up 1R, the stop moves to the entry
            if (!o.breakeven && o.risk > 0 && high >= o.entry + o.risk) {
                o.stop = o.entry;
                o.breakeven = true;
                store.write(OPEN, open);
            }
            return;
        }
        PlanAlert a = alert("SELL NOW", o.symbol, exit, why);
        a.entry = o.entry;
        a.stop = o.stop;
        a.target = o.target;
        a.grade = o.grade;
        a.type = o.type;
        a.resultPct = Math.round((exit / o.entry - 1) * 10_000) / 100.0;
        a.sent = notifier.send(String.format("SELL NOW %s at %.2f", o.symbol, exit),
                why + String.format("%nEntry %.2f · result %+.2f%%", o.entry, a.resultPct));
        open.remove(o.symbol);
        saveAlerts();
    }

    private void buy(String sym, StockRow row, TradePlan.Setup st, double wr, double last) {
        OpenTrade o = new OpenTrade();
        o.symbol = sym;
        o.name = row == null ? null : row.name;
        o.type = st.type();
        o.grade = st.grade();
        o.date = clock.now().toLocalDate().toString();
        o.entryAt = OffsetDateTime.now().toString();
        o.entry = Double.isNaN(last) ? st.entry() : last;
        double risk = st.entry() - st.stop();
        o.stop = round(o.entry - risk);
        o.risk = round(risk);
        o.target = round(o.entry + (st.target() - st.entry()));
        o.rr = round(st.rr());
        o.targetSource = st.targetSource();
        o.winRate = wr;
        o.last = last;
        o.confluence = st.confluence();
        open.put(sym, o);
        PlanAlert a = alert("BUY NOW", sym, o.entry, String.join(", ", st.confluence()));
        a.entry = o.entry;
        a.stop = o.stop;
        a.target = o.target;
        a.rr = o.rr;
        a.grade = st.grade();
        a.type = st.type();
        a.winRate = wr;
        a.sent = notifier.send(String.format("BUY NOW %s at %.2f (grade %s, wins %.0f%%)", sym, o.entry, st.grade(), wr),
                String.format("Stop %.2f · target %.2f (%s) · R:R %.1f%n%s %s%n%s", o.stop, o.target, st.targetSource(), st.rr(),
                        st.type().equals("RETEST") ? "Retest of" : "Breakout over", st.zone() == null ? "the prior day high" : st.zone().label(),
                        String.join(", ", st.confluence())));
        saveAlerts();
    }

    private PlanAlert alert(String action, String sym, double price, String reason) {
        PlanAlert a = new PlanAlert();
        a.id = sym + "|" + action + "|" + OffsetDateTime.now();
        a.symbol = sym;
        a.action = action;
        a.at = clock.now().toOffsetDateTime().toString();
        a.price = round(price);
        a.reason = reason;
        alerts.add(a);
        return a;
    }

    private void saveAlerts() {
        while (alerts.size() > 500) alerts.remove(0);
        store.write(ALERTS, new ArrayList<>(alerts));
        store.write(OPEN, open);
    }

    // ---- backtest --------------------------------------------------------------------------------------

    public boolean backtestAsync() {
        if (testing.get()) return false;
        pool.submit(this::runBacktest);
        return true;
    }

    /** Replays the plan over the last 60 days of 5-minute data for every compliant screened name. */
    public void runBacktest() {
        if (!testing.compareAndSet(false, true)) return;
        try {
            TradePlan.Settings s = settings;
            List<String> syms = screen.snapshot().rows.stream()
                    .filter(r -> (r.screenPass || r.userAdded) && "COMPLIANT".equals(r.zoya)).map(r -> r.symbol).distinct().toList();
            List<Bar> spy15 = yahoo.bars("SPY", "15m", "60d");
            List<TradePlan.Trade> all = Collections.synchronizedList(new ArrayList<>());
            List<String> errors = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> fs = new ArrayList<>();
            for (String sym : syms) {
                fs.add(pool.submit(() -> {
                    try {
                        TradePlan.Bars b = new TradePlan.Bars(yahoo.bars(sym, "1d", "2y"), yahoo.bars(sym, "60m", "1y"),
                                yahoo.bars(sym, "15m", "60d"), yahoo.bars(sym, "5m", "60d"), spy15);
                        all.addAll(TradePlan.backtest(sym, b, s));
                    } catch (Exception e) {
                        errors.add(sym + ": " + e.getMessage());
                    }
                }));
            }
            for (Future<?> f : fs) { try { f.get(10, TimeUnit.MINUTES); } catch (Exception ignored) { /* counted in errors */ } }
            List<TradePlan.Trade> sorted = new ArrayList<>(all);
            sorted.sort(Comparator.comparingLong(TradePlan.Trade::entryTime));
            Backtest bt = new Backtest();
            bt.generatedAt = OffsetDateTime.now().toString();
            bt.stocks = syms.size();
            bt.from = sorted.isEmpty() ? null : date(sorted.get(0).entryTime());
            bt.to = sorted.isEmpty() ? null : date(sorted.get(sorted.size() - 1).entryTime());
            bt.stats = TradePlan.stats(sorted);
            bt.recentTrades = sorted.size() > 60 ? new ArrayList<>(sorted.subList(sorted.size() - 60, sorted.size())) : sorted;
            Collections.reverse(bt.recentTrades);
            bt.errors = new ArrayList<>(errors);
            backtest = bt;
            store.write(BACKTEST, bt);
            log.info("Plan backtest: {} stocks, {} trades, overall {}", syms.size(), sorted.size(), bt.stats.get("ALL"));
        } catch (Exception e) {
            log.warn("Plan backtest failed: {}", e.getMessage());
        } finally {
            testing.set(false);
        }
    }

    /** The backtested win rate for this setup's type and grade (falling back to the grade across types). */
    Double winRate(TradePlan.Setup st) {
        Map<String, Object> m = statFor(st);
        return m == null ? null : ((Number) m.get("winRate")).doubleValue();
    }

    int trades(TradePlan.Setup st) {
        Map<String, Object> m = statFor(st);
        return m == null ? 0 : ((Number) m.get("trades")).intValue();
    }

    private Map<String, Object> statFor(TradePlan.Setup st) {
        Backtest bt = backtest;
        if (bt == null) return null;
        Map<String, Object> m = bt.stats.get(st.key());
        if (m != null && ((Number) m.get("trades")).intValue() >= settings.minTrades) return m;
        return bt.stats.get("ALL-" + st.grade());
    }

    // ---- views -----------------------------------------------------------------------------------------

    public Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", settings);
        m.put("lastScan", lastScan);
        m.put("scanned", lastScanned);
        m.put("note", lastScanNote);
        m.put("scanning", scanning.get());
        m.put("testing", testing.get());
        m.put("open", open.values());
        m.put("watching", watching);
        m.put("backtest", backtest);
        Map<String, Boolean> gate = new LinkedHashMap<>();
        if (backtest != null) {
            for (var e : backtest.stats.entrySet()) {
                Map<String, Object> v = e.getValue();
                gate.put(e.getKey(), ((Number) v.get("winRate")).doubleValue() >= settings.minWinRate
                        && ((Number) v.get("trades")).intValue() >= settings.minTrades);
            }
        }
        m.put("gate", gate);
        String today = clock.now().toLocalDate().toString();
        m.put("today", alerts.stream().filter(a -> a.at.startsWith(today)).sorted(Comparator.comparing((PlanAlert a) -> a.at).reversed()).toList());
        return m;
    }

    public List<PlanAlert> recent() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return alerts.stream().filter(a -> OffsetDateTime.parse(a.at).toInstant().isAfter(since)).toList();
    }

    public List<PlanAlert> allAlerts() {
        List<PlanAlert> l = new ArrayList<>(alerts);
        Collections.reverse(l);
        return l;
    }

    public TradePlan.Settings saveSettings(TradePlan.Settings s) {
        s.validate();
        settings = s;
        store.write(SETTINGS, s);
        return s;
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    /** Daily and hourly candles change slowly: keep them 30 minutes. */
    private List<Bar> slow(String sym, String interval, String range) throws Exception {
        String key = sym + "|" + interval;
        var c = slowCache.get(key);
        if (c != null && c.getKey().isAfter(Instant.now().minus(Duration.ofMinutes(30)))) return c.getValue();
        List<Bar> b = yahoo.bars(sym, interval, range);
        slowCache.put(key, Map.entry(Instant.now(), b));
        return b;
    }

    private static String date(long epoch) { return Instant.ofEpochSecond(epoch).atZone(MarketClock.ET).toLocalDate().toString(); }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
