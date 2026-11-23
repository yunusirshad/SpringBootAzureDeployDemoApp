package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.analysis.PvConfirm;
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
 * PV Confirm for every screened name (and every open PV trade, even after it leaves the screen).
 * Runs with the screen every 15 minutes on trading days from 7:00 AM to 4:00 PM ET, and at 4:15 PM ET, when the
 * daily bar counts as complete and the BUY, SELL and READY TO BUY alerts go out once.
 */
@Service
public class PvService {

    private static final Logger log = LoggerFactory.getLogger(PvService.class);
    private static final String SETTINGS = "pv-settings.json", OPEN = "pv-open.json", ALERTS = "pv-alerts.json", LAST = "pv-last.json";
    static final LocalTime DAY_COMPLETE = LocalTime.of(16, 15);
    private static final Duration BAR_CACHE = Duration.ofMinutes(10);

    public static class Row {
        public String symbol, name, section;   // section: UPTREND or DOWNTREND
        public boolean onScreen;
        public String status, exitReason, note;
        public Double entry, stop, exit, rsi2, volPct, price, sma50, sma200, atr;
        public boolean entryEstimated, nearSetup;
        public Integer rulesPassed, barsHeld;
        public PvConfirm.Rules rules;
        public String entryDate, signalDate, barDate;
        public List<PvConfirm.Trade> trades = List.of();
        public String error;
    }

    public static class PvAlert {
        public String id, symbol, status, reason, barDate, at;
        public Double price, entry, stop;
    }

    public static class State {
        public String generatedAt, barDate, settledNote;
        public boolean dayComplete;
        public Double spyClose, spySma200;
        public Boolean spyAbove;
        public List<Row> rows = new ArrayList<>();
        public String lastAlertDay;
    }

    private final YahooClient yahoo;
    private final ScreenService screen;
    private final JsonStore store;
    private final MarketClock clock;
    private volatile PvConfirm.Settings settings;
    private final Map<String, String> open;          // symbol -> entry date of a trade being tracked
    private final List<PvAlert> alerts;
    private volatile State state;
    private final Map<String, Map.Entry<Instant, List<Bar>>> barCache = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    public PvService(YahooClient yahoo, ScreenService screen, JsonStore store, MarketClock clock) {
        this.yahoo = yahoo;
        this.screen = screen;
        this.store = store;
        this.clock = clock;
        this.settings = store.read(SETTINGS, new TypeReference<PvConfirm.Settings>() {}, new PvConfirm.Settings());
        this.open = new ConcurrentHashMap<>(store.read(OPEN, new TypeReference<Map<String, String>>() {}, new HashMap<>()));
        this.alerts = new CopyOnWriteArrayList<>(store.read(ALERTS, new TypeReference<List<PvAlert>>() {}, new ArrayList<>()));
        this.state = store.read(LAST, new TypeReference<State>() {}, new State());
    }

    public State state() { return state; }

    public PvConfirm.Settings settings() { return settings; }

    public List<PvAlert> alerts() {
        List<PvAlert> l = new ArrayList<>(alerts);
        Collections.reverse(l);
        return l;
    }

    /** Alerts from the last 24 hours, for browser notifications. */
    public List<PvAlert> recentAlerts() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return alerts.stream().filter(a -> a.at != null && OffsetDateTime.parse(a.at).toInstant().isAfter(since)).toList();
    }

    public PvConfirm.Settings saveSettings(PvConfirm.Settings s) {
        s.validate();
        settings = s;
        store.write(SETTINGS, s);
        return s;
    }

    // ---- schedules ---------------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        pool.submit(() -> {
            // wait for the first screen so there are names to read
            for (int i = 0; i < 120 && screen.snapshot().rows.isEmpty(); i++) {
                try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            }
            refresh();
        });
    }

    /** With the screen: every 15 minutes, 7:00 AM to 4:00 PM ET on trading days (a few minutes after it). */
    @Scheduled(cron = "0 5/15 7-16 * * MON-FRI", zone = "America/New_York")
    public void scheduled() {
        ZonedDateTime now = clock.now();
        if (clock.isTradingDay(now.toLocalDate()) && !now.toLocalTime().isAfter(LocalTime.of(16, 5))) refresh();
    }

    /** 4:15 PM ET: the daily bar is complete; read it and send the alerts. */
    @Scheduled(cron = "0 15 16 * * MON-FRI", zone = "America/New_York")
    public void afterClose() {
        if (clock.isTradingDay(clock.now().toLocalDate())) refresh();
    }

    // ---- the run -------------------------------------------------------------------------------

    public boolean refreshAsync() {
        if (running.get()) return false;
        pool.submit(this::refresh);
        return true;
    }

    public void refresh() {
        if (!running.compareAndSet(false, true)) return;
        try {
            run();
        } catch (Exception e) {
            log.warn("PV Confirm failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() { return running.get(); }

    private void run() throws Exception {
        PvConfirm.Settings s = settings;
        ZonedDateTime now = clock.now();
        LocalDate today = now.toLocalDate();
        boolean dayComplete = !clock.isTradingDay(today) || !now.toLocalTime().isBefore(DAY_COMPLETE);
        boolean marketOpened = clock.isTradingDay(today) && !now.toLocalTime().isBefore(LocalTime.of(9, 30));

        // SPY
        List<Bar> spyBars = completedOnly(bars("SPY"), today, dayComplete).completed();
        Map<LocalDate, Boolean> spyAbove = PvConfirm.spyAbove(spyBars);

        // names: screened or added, plus open trades
        Map<String, StockRow> onScreen = new LinkedHashMap<>();
        // only Zoya-compliant names; a trade already open is still tracked to its exit
        for (StockRow r : screen.snapshot().rows) if ((r.screenPass || r.userAdded) && "COMPLIANT".equals(r.zoya)) onScreen.put(r.symbol, r);
        LinkedHashSet<String> symbols = new LinkedHashSet<>(onScreen.keySet());
        symbols.addAll(open.keySet());

        Map<String, Future<Row>> fs = new LinkedHashMap<>();
        for (String sym : symbols) fs.put(sym, pool.submit(() -> one(sym, onScreen.get(sym), today, dayComplete, marketOpened, spyAbove, s)));
        List<Row> rows = new ArrayList<>();
        for (var e : fs.entrySet()) {
            try { rows.add(e.getValue().get(90, TimeUnit.SECONDS)); } catch (Exception ex) {
                Row r = new Row();
                r.symbol = e.getKey();
                r.section = "UPTREND";
                r.status = PvConfirm.Status.WAIT.name();
                r.error = "Could not read daily bars";
                rows.add(r);
            }
        }

        // track open trades so they stay on the list after leaving the screen; a SELL stays until the next bar
        for (Row r : rows) {
            if (r.error != null) continue;
            if ("IN_TRADE".equals(r.status) || "SELL".equals(r.status)) open.put(r.symbol, String.valueOf(r.entryDate));
            else open.remove(r.symbol);
        }
        store.write(OPEN, open);

        State st = new State();
        st.generatedAt = OffsetDateTime.now().toString();
        st.dayComplete = dayComplete;
        st.barDate = spyBars.isEmpty() ? null : PvConfirm.date(spyBars.get(spyBars.size() - 1)).toString();
        st.settledNote = dayComplete ? "Daily bar complete" : "Today's bar is still forming; signals use the last completed bar until 4:15 PM ET";
        if (!spyBars.isEmpty()) {
            Bar last = spyBars.get(spyBars.size() - 1);
            st.spyClose = last.close();
            double sum = 0;
            int n = Math.min(200, spyBars.size());
            for (int i = spyBars.size() - n; i < spyBars.size(); i++) sum += spyBars.get(i).close();
            st.spySma200 = n == 200 ? Math.round(sum / n * 100) / 100.0 : null;
            st.spyAbove = spyAbove.get(PvConfirm.date(last));
        }
        st.rows = rows;
        st.lastAlertDay = state.lastAlertDay;

        // alerts: once per trading day, after 4:15 PM ET
        if (s.alerts && clock.isTradingDay(today) && dayComplete && !today.toString().equals(st.lastAlertDay)) {
            int n = sendAlerts(rows, today);
            st.lastAlertDay = today.toString();
            log.info("PV Confirm alerts for {}: {}", today, n);
        }
        state = st;
        store.write(LAST, st);
        log.info("PV Confirm: {} names, {} up, {} in trade", rows.size(),
                rows.stream().filter(r -> "UPTREND".equals(r.section)).count(),
                rows.stream().filter(r -> "IN_TRADE".equals(r.status)).count());
    }

    private Row one(String sym, StockRow screenRow, LocalDate today, boolean dayComplete, boolean marketOpened,
                    Map<LocalDate, Boolean> spyAbove, PvConfirm.Settings s) throws Exception {
        Split sp = completedOnly(bars(sym), today, dayComplete);
        Bar live = marketOpened && !dayComplete ? sp.live() : null;
        PvConfirm.Result res = PvConfirm.evaluate(sp.completed(), live, spyAbove, s);
        Row r = new Row();
        r.symbol = sym;
        r.name = screenRow != null ? screenRow.name : null;
        r.onScreen = screenRow != null;
        // the section follows the trend check, SMA50 above SMA200, on the same completed bars
        r.section = res.trendUp() ? "UPTREND" : "DOWNTREND";
        r.status = res.status().name();
        r.exitReason = res.exitReason();
        r.note = res.note() + (r.onScreen ? "" : ". No longer on the screen; tracked until it exits");
        r.entry = num(res.entry());
        r.entryEstimated = res.entryEstimated();
        r.stop = num(res.stop());
        r.exit = num(res.exit());
        r.rsi2 = num(res.rsi());
        r.volPct = num(res.volPct());
        r.rules = res.rules();
        r.rulesPassed = res.rules().passed();
        r.nearSetup = res.nearSetup();
        r.barsHeld = res.barsHeld();
        r.entryDate = str(res.entryDate());
        r.signalDate = str(res.signalDate());
        r.barDate = str(res.barDate());
        r.price = screenRow != null && screenRow.price != null ? screenRow.price : num(res.close());
        r.sma50 = num(res.sma50());
        r.sma200 = num(res.sma200());
        r.atr = num(res.atr());
        r.trades = res.trades();
        return r;
    }

    private int sendAlerts(List<Row> rows, LocalDate today) {
        int n = 0;
        for (Row r : rows) {
            if (r.error != null) continue;
            boolean send = switch (r.status) {
                case "BUY", "SELL", "READY_TO_BUY" -> true;
                default -> false;
            };
            if (!send) continue;
            String id = r.symbol + "|" + r.status + "|" + today;
            if (alerts.stream().anyMatch(a -> a.id.equals(id))) continue;
            PvAlert a = new PvAlert();
            a.id = id;
            a.symbol = r.symbol;
            a.status = r.status;
            a.reason = r.exitReason != null ? r.exitReason : r.note;
            a.barDate = r.barDate;
            a.price = r.price;
            a.entry = r.entry;
            a.stop = r.stop;
            a.at = OffsetDateTime.now().toString();
            alerts.add(a);
            n++;
        }
        while (alerts.size() > 300) alerts.remove(0);
        store.write(ALERTS, new ArrayList<>(alerts));
        return n;
    }

    // ---- data -----------------------------------------------------------------------------------

    record Split(List<Bar> completed, Bar live) {}

    /** Today's daily bar counts as complete only from 4:15 PM ET; before that it is the live bar. */
    static Split completedOnly(List<Bar> daily, LocalDate today, boolean dayComplete) {
        if (daily.isEmpty()) return new Split(daily, null);
        Bar last = daily.get(daily.size() - 1);
        if (PvConfirm.date(last).equals(today) && !dayComplete) return new Split(daily.subList(0, daily.size() - 1), last);
        return new Split(daily, null);
    }

    private List<Bar> bars(String sym) throws Exception {
        var c = barCache.get(sym);
        if (c != null && c.getKey().isAfter(Instant.now().minus(BAR_CACHE))) return c.getValue();
        List<Bar> b = yahoo.bars(sym, "1d", "2y");
        barCache.put(sym, Map.entry(Instant.now(), b));
        return b;
    }

    private static Double num(double d) { return Double.isNaN(d) || Double.isInfinite(d) ? null : Math.round(d * 10_000) / 10_000.0; }

    private static Double num(Double d) { return d == null ? null : num(d.doubleValue()); }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
