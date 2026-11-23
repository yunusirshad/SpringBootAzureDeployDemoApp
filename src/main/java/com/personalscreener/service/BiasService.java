package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.analysis.Indicators;
import com.personalscreener.analysis.MarketBias;
import com.personalscreener.analysis.SessionPhases;
import com.personalscreener.data.Bar;
import com.personalscreener.data.YahooClient;
import com.personalscreener.market.MarketClock;
import com.personalscreener.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The market-bias bot: at 9:30 AM ET each trading day it reads futures, SPY, VIX and the overnight sessions, calls
 * the day Bullish, Bearish or Neutral, and alerts in the app and (when set up) on Telegram or ntfy. After the close
 * it records whether SPY finished above or below its open, so the page can show how often the call was right.
 */
@Service
public class BiasService {

    private static final Logger log = LoggerFactory.getLogger(BiasService.class);
    private static final String FILE = "bias-history.json";
    static final LocalTime CALL_TIME = LocalTime.of(9, 30), OUTCOME_TIME = LocalTime.of(16, 20);

    public static class Record {
        public String date, at, bias, strength, lean, summary;
        public int score, max;
        public List<MarketBias.Factor> factors = List.of();
        /** SPY's open and close that day, and whether the call matched (null for Neutral or before the close). */
        public Double spyOpen, spyClose, spyChangePct;
        public String outcome;
        public Boolean correct;
        public List<String> sent = List.of();
        public boolean late;
    }

    private final YahooClient yahoo;
    private final MarketClock clock;
    private final JsonStore store;
    private final Notifier notifier;
    private final List<Record> history;
    private volatile Record preview;
    private volatile Instant previewAt = Instant.EPOCH;

    public BiasService(YahooClient yahoo, MarketClock clock, JsonStore store, Notifier notifier) {
        this.yahoo = yahoo;
        this.clock = clock;
        this.store = store;
        this.notifier = notifier;
        this.history = new CopyOnWriteArrayList<>(store.read(FILE, new TypeReference<List<Record>>() {}, new ArrayList<>()));
    }

    // ---- schedule ------------------------------------------------------------------------------

    /** The call, at 9:30 AM ET on trading days. */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "America/New_York")
    public void atOpen() {
        if (clock.isTradingDay(clock.now().toLocalDate())) call(true, false);
    }

    /** After the close: did SPY finish above or below its open? */
    @Scheduled(cron = "0 20 16 * * MON-FRI", zone = "America/New_York")
    public void afterClose() { fillOutcomes(); }

    /** Started after 9:30 on a trading day with no call yet: make it now, in the app only (marked late). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        new Thread(() -> {
            try {
                ZonedDateTime now = clock.now();
                LocalDate d = now.toLocalDate();
                if (clock.isTradingDay(d) && !now.toLocalTime().isBefore(CALL_TIME) && today(d) == null) call(false, true);
                fillOutcomes();
            } catch (Exception e) {
                log.warn("Bias catch-up failed: {}", e.getMessage());
            }
        }, "bias-start").start();
    }

    // ---- the call --------------------------------------------------------------------------------

    /** Makes today's call, saves it and alerts. {@code phone}: also send to Telegram / ntfy. */
    public synchronized Record call(boolean phone, boolean late) {
        Record r = compute();
        r.late = late;
        if (phone) r.sent = notifier.send(title(r), body(r));
        history.removeIf(x -> x.date.equals(r.date));
        history.add(r);
        while (history.size() > 120) history.remove(0);
        store.write(FILE, new ArrayList<>(history));
        log.info("Market bias {}: {}", r.date, r.summary);
        return r;
    }

    /** The bias right now without saving or alerting, kept for 5 minutes (for the card before 9:30 or on demand). */
    public Record preview(boolean force) {
        if (force || preview == null || previewAt.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
            preview = compute();
            previewAt = Instant.now();
        }
        return preview;
    }

    public List<String> testAlert() {
        if (notifier.channels().isEmpty()) return List.of("No phone channel is set up: set SCREENER_NTFY_TOPIC, or SCREENER_TELEGRAM_TOKEN and SCREENER_TELEGRAM_CHAT_ID.");
        return notifier.send("Personal Screener test", "Phone alerts work. The market bias will arrive at 9:30 AM ET on trading days.");
    }

    public List<String> channels() { return notifier.channels(); }

    public Record today(LocalDate d) {
        return history.stream().filter(x -> x.date.equals(d.toString())).findFirst().orElse(null);
    }

    public List<Record> history() {
        List<Record> l = new ArrayList<>(history);
        Collections.reverse(l);
        return l;
    }

    /** Calls with a result, and how many were right. */
    public Map<String, Object> accuracy() {
        long judged = history.stream().filter(x -> x.correct != null).count();
        long right = history.stream().filter(x -> Boolean.TRUE.equals(x.correct)).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("judged", judged);
        m.put("right", right);
        m.put("pct", judged == 0 ? null : Math.round(100.0 * right / judged));
        return m;
    }

    Record compute() {
        ZonedDateTime now = clock.now();
        LocalDate today = now.toLocalDate();
        // a call made after 9:30 (a late start) still uses only what was known at 9:30
        ZonedDateTime cut = clock.isTradingDay(today) && now.toLocalTime().isAfter(CALL_TIME)
                ? today.atTime(CALL_TIME).atZone(MarketClock.ET) : now;
        long nowSec = cut.toEpochSecond();
        LocalDate prev = previousTradingDay(today);
        MarketBias.Inputs in;
        double esPct = Double.NaN, nqPct = Double.NaN, gap = Double.NaN, close = Double.NaN, ema20 = Double.NaN,
                sma50 = Double.NaN, sma200 = Double.NaN, m5 = Double.NaN, vix = Double.NaN, vixAvg = Double.NaN;
        SessionPhases.Session london = null, asia = null;
        try {
            List<Bar> es = yahoo.chart("ES=F", "5m", "5d", true).bars();
            esPct = overnight(es, prev, nowSec);
            List<Bar> esCut = es.stream().filter(b -> b.time() + 300 <= nowSec).toList();   // 5-minute bars finished by then
            for (SessionPhases.Session s : SessionPhases.read(esCut, nowSec, false)) {
                if (!s.tradeDate().equals(today)) continue;
                if (s.session().equals("London")) london = s;
                if (s.session().equals("Asia")) asia = s;
            }
        } catch (Exception e) { log.warn("Bias: ES failed: {}", e.getMessage()); }
        try { nqPct = overnight(yahoo.chart("NQ=F", "5m", "5d", true).bars(), prev, nowSec); }
        catch (Exception e) { log.warn("Bias: NQ failed: {}", e.getMessage()); }
        try {
            List<Bar> daily = completedDaily(yahoo.bars("SPY", "1d", "1y"), today);
            double[] c = Indicators.closes(daily);
            close = c[c.length - 1];
            ema20 = Indicators.ema(c, 20);
            sma50 = Indicators.sma(c, 50);
            sma200 = Indicators.sma(c, 200);
            m5 = c.length > 5 ? (close / c[c.length - 6] - 1) * 100 : Double.NaN;
            List<Bar> intraday = yahoo.chart("SPY", "5m", "1d", true).bars();
            double last = lastPrice(intraday, nowSec);
            gap = Double.isNaN(last) ? Double.NaN : (last / close - 1) * 100;
        } catch (Exception e) { log.warn("Bias: SPY failed: {}", e.getMessage()); }
        try {
            List<Bar> v = completedDaily(yahoo.bars("^VIX", "1d", "1mo"), today);
            double[] c = Indicators.closes(v);
            vix = c[c.length - 1];
            vixAvg = c.length >= 10 ? Indicators.sma(c, 10) : Double.NaN;
        } catch (Exception e) { log.warn("Bias: VIX failed: {}", e.getMessage()); }

        in = new MarketBias.Inputs(esPct, nqPct, gap, close, ema20, sma50, sma200, m5, vix, vixAvg, london, asia);
        MarketBias.Result res = MarketBias.score(in);
        Record r = new Record();
        r.date = today.toString();
        r.at = OffsetDateTime.now().toString();
        r.bias = res.bias();
        r.strength = res.strength();
        r.lean = res.lean();
        r.summary = res.summary();
        r.score = res.score();
        r.max = res.max();
        r.factors = res.factors();
        return r;
    }

    /** Fills SPY's open, close and whether the call was right, for finished days. */
    public synchronized void fillOutcomes() {
        boolean changed = false;
        ZonedDateTime now = clock.now();
        try {
            List<Bar> daily = yahoo.bars("SPY", "1d", "1mo");
            for (Record r : history) {
                if (r.outcome != null) continue;
                LocalDate d = LocalDate.parse(r.date);
                if (d.equals(now.toLocalDate()) && now.toLocalTime().isBefore(LocalTime.of(16, 15))) continue;
                for (Bar b : daily) {
                    if (!date(b).equals(d)) continue;
                    r.spyOpen = b.open();
                    r.spyClose = b.close();
                    r.spyChangePct = Math.round((b.close() / b.open() - 1) * 10_000) / 100.0;
                    r.outcome = b.close() > b.open() ? "UP" : b.close() < b.open() ? "DOWN" : "FLAT";
                    r.correct = r.bias.equals("Neutral") ? null
                            : (r.bias.equals("Bullish") && r.outcome.equals("UP")) || (r.bias.equals("Bearish") && r.outcome.equals("DOWN"));
                    changed = true;
                }
            }
        } catch (Exception e) {
            log.warn("Bias outcome failed: {}", e.getMessage());
        }
        if (changed) store.write(FILE, new ArrayList<>(history));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    public static String title(Record r) {
        return "Market bias " + r.date + ": " + r.bias.toUpperCase() + (r.bias.equals("Neutral") ? "" : " (" + r.strength + ")");
    }

    static String body(Record r) {
        StringBuilder b = new StringBuilder(r.summary).append('\n');
        for (MarketBias.Factor f : r.factors) {
            b.append(f.points() > 0 ? "▲ " : f.points() < 0 ? "▼ " : "· ").append(f.name()).append(": ").append(f.reading())
                    .append(f.points() == 0 ? "" : " (" + (f.points() > 0 ? "+" : "") + f.points() + ")").append('\n');
        }
        return b.toString().trim();
    }

    /** Percent from the last price at or before 4:00 PM ET on {@code prev} to the latest price now. */
    static double overnight(List<Bar> bars, LocalDate prev, long nowSec) {
        long ref = prev.atTime(16, 0).atZone(MarketClock.ET).toEpochSecond();
        double base = Double.NaN;
        for (Bar b : bars) if (b.time() < ref && date(b).equals(prev)) base = b.close();
        double last = lastPrice(bars, nowSec);
        return Double.isNaN(base) || Double.isNaN(last) || base <= 0 ? Double.NaN : (last / base - 1) * 100;
    }

    /** The close of the last 5-minute bar that finished by {@code nowSec}. */
    static double lastPrice(List<Bar> bars, long nowSec) {
        double last = Double.NaN;
        for (Bar b : bars) if (b.time() + 300 <= nowSec) last = b.close();
        return last;
    }

    /** Daily bars before today (today's bar is still forming at 9:30). */
    static List<Bar> completedDaily(List<Bar> daily, LocalDate today) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : daily) if (date(b).isBefore(today)) out.add(b);
        return out;
    }

    LocalDate previousTradingDay(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!clock.isTradingDay(p)) p = p.minusDays(1);
        return p;
    }

    static LocalDate date(Bar b) { return Instant.ofEpochSecond(b.time()).atZone(MarketClock.ET).toLocalDate(); }
}
