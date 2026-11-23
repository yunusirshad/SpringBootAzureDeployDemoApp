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
        /** Hourly readings during the session, 10:00 AM to 4:00 PM ET. */
        public List<Intraday> intraday = new ArrayList<>();
    }

    public static class Intraday {
        public String time, at, bias, strength, lean, summary, flippedFrom;
        public int score, max;
        public List<MarketBias.Factor> factors = List.of();
        public List<String> sent = List.of();
    }

    private final YahooClient yahoo;
    private final MarketClock clock;
    private final JsonStore store;
    private final Notifier notifier;
    private final com.personalscreener.data.TradingViewClient tv;
    private final List<Record> history;
    private volatile Record preview;
    private volatile Instant previewAt = Instant.EPOCH;

    public BiasService(YahooClient yahoo, MarketClock clock, JsonStore store, Notifier notifier,
                       com.personalscreener.data.TradingViewClient tv) {
        this.tv = tv;
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

    /** On the hour from 10:00 AM to 4:00 PM ET: the intraday reading, with a phone alert when the bias flips. */
    @Scheduled(cron = "0 0 10-16 * * MON-FRI", zone = "America/New_York")
    public void hourly() {
        ZonedDateTime now = clock.now();
        if (clock.isTradingDay(now.toLocalDate())) intradayCall(now.withMinute(0).withSecond(0).withNano(0), true);
    }

    /**
     * Reads the session at {@code cut} (a whole hour), adds it to today's record and, when {@code phone} is set and
     * the bias differs from the previous reading (or the 9:30 call), alerts Telegram / ntfy.
     */
    public synchronized Intraday intradayCall(ZonedDateTime cut, boolean phone) {
        LocalDate d = cut.toLocalDate();
        Record day = today(d);
        if (day == null) day = call(false, true);
        Intraday r = computeIntraday(cut);
        String before = day.intraday.isEmpty() ? day.bias : day.intraday.get(day.intraday.size() - 1).bias;
        if (!r.bias.equals(before)) r.flippedFrom = before;
        if (phone && r.flippedFrom != null) r.sent = notifier.send(intradayTitle(r, d), body(r.summary, r.factors));
        day.intraday.removeIf(x -> x.time.equals(r.time));
        day.intraday.add(r);
        store.write(FILE, new ArrayList<>(history));
        log.info("Intraday bias {} {}: {}{}", d, r.time, r.summary, r.flippedFrom == null ? "" : " (flipped from " + r.flippedFrom + ")");
        return r;
    }

    public static String intradayTitle(Intraday r, LocalDate d) {
        return "Intraday bias " + d + " " + r.time + " ET: " + r.bias.toUpperCase()
                + (r.bias.equals("Neutral") ? "" : " (" + r.strength + ")")
                + (r.flippedFrom == null ? ", unchanged" : ", flipped from " + r.flippedFrom);
    }

    /** The session read at {@code cut} from 5-minute bars that finished by then. */
    public Intraday computeIntraday(ZonedDateTime cut) {
        LocalDate d = cut.toLocalDate();
        long cutSec = cut.toEpochSecond(), open = d.atTime(9, 30).atZone(MarketClock.ET).toEpochSecond();
        double spy = Double.NaN, orH = Double.NaN, orL = Double.NaN, spyV = Double.NaN, qqq = Double.NaN, qqqV = Double.NaN,
                iwm = Double.NaN, iwmV = Double.NaN, spyOpen = Double.NaN, c15 = Double.NaN, e15 = Double.NaN, e15b = Double.NaN,
                vix = Double.NaN, vixOpen = Double.NaN;
        Integer up = null, dn = null;
        SessionPhases.Session ny = null;
        try {
            List<Bar> b = session(yahoo.chart("SPY", "5m", "1d", false).bars(), open, cutSec);
            if (!b.isEmpty()) {
                spy = b.get(b.size() - 1).close();
                spyOpen = b.get(0).open();
                spyV = Indicators.vwap(b);
                if (cutSec >= open + 1800) {
                    orH = Double.NEGATIVE_INFINITY; orL = Double.POSITIVE_INFINITY;
                    for (Bar x : b) if (x.time() < open + 1800) { orH = Math.max(orH, x.high()); orL = Math.min(orL, x.low()); }
                }
            }
        } catch (Exception e) { log.warn("Intraday: SPY failed: {}", e.getMessage()); }
        try {
            List<Bar> b = session(yahoo.chart("QQQ", "5m", "1d", false).bars(), open, cutSec);
            if (!b.isEmpty()) { qqq = b.get(b.size() - 1).close(); qqqV = Indicators.vwap(b); }
        } catch (Exception e) { log.warn("Intraday: QQQ failed: {}", e.getMessage()); }
        try {
            List<Bar> b = session(yahoo.chart("IWM", "5m", "1d", false).bars(), open, cutSec);
            if (!b.isEmpty()) { iwm = b.get(b.size() - 1).close(); iwmV = Indicators.vwap(b); }
        } catch (Exception e) { log.warn("Intraday: IWM failed: {}", e.getMessage()); }
        try {
            List<Bar> b15 = yahoo.chart("SPY", "15m", "5d", false).bars().stream().filter(x -> x.time() + 900 <= cutSec).toList();
            double[] c = Indicators.closes(b15);
            double[] ema = Indicators.emaSeries(c, 20);
            if (c.length > 23) { c15 = c[c.length - 1]; e15 = ema[c.length - 1]; e15b = ema[c.length - 4]; }
        } catch (Exception e) { log.warn("Intraday: SPY 15m failed: {}", e.getMessage()); }
        try {
            List<Bar> b = session(yahoo.chart("^VIX", "5m", "1d", false).bars(), open, cutSec);
            if (!b.isEmpty()) { vix = b.get(b.size() - 1).close(); vixOpen = b.get(0).open(); }
        } catch (Exception e) { log.warn("Intraday: VIX failed: {}", e.getMessage()); }
        // breadth is a live count, so it is used only when the reading is for now (not a late catch-up)
        if (Math.abs(Instant.now().getEpochSecond() - cutSec) <= 900) {
            try {
                int[] nas = tv.breadth("NASDAQ"), nyse = tv.breadth("NYSE");
                up = nas[0] + nyse[0];
                dn = nas[1] + nyse[1];
            } catch (Exception e) { log.debug("Intraday: breadth unavailable: {}", e.getMessage()); }
        }
        try {
            List<Bar> es = yahoo.chart("ES=F", "5m", "5d", true).bars().stream().filter(x -> x.time() + 300 <= cutSec).toList();
            for (SessionPhases.Session s : SessionPhases.read(es, cutSec, false)) {
                if (s.tradeDate().equals(d) && s.session().equals("New York")) ny = s;
            }
        } catch (Exception e) { log.warn("Intraday: ES failed: {}", e.getMessage()); }

        MarketBias.Result res = com.personalscreener.analysis.IntradayBias.score(new com.personalscreener.analysis.IntradayBias.Inputs(
                spy, orH, orL, spyV, qqq, qqqV, iwm, iwmV, spyOpen, c15, e15, e15b, up, dn, vix, vixOpen, ny));
        Intraday r = new Intraday();
        r.time = String.format("%d:%02d", cut.getHour() > 12 ? cut.getHour() - 12 : cut.getHour(), cut.getMinute()) + (cut.getHour() >= 12 ? " PM" : " AM");
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

    /** Regular-session 5-minute bars from the open that finished by {@code cutSec}. */
    static List<Bar> session(List<Bar> bars, long open, long cutSec) {
        return bars.stream().filter(b -> b.time() >= open && b.time() + 300 <= cutSec).toList();
    }

    /** Started after 9:30 on a trading day with no call yet: make it now, in the app only (marked late). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        new Thread(() -> {
            try {
                ZonedDateTime now = clock.now();
                LocalDate d = now.toLocalDate();
                if (clock.isTradingDay(d) && !now.toLocalTime().isBefore(CALL_TIME) && today(d) == null) call(false, true);
                // hours missed while the app was off (no phone alerts for these)
                if (clock.isTradingDay(d) && today(d) != null) {
                    for (int h = 10; h <= Math.min(16, now.getHour()); h++) {
                        ZonedDateTime cut = d.atTime(h, 0).atZone(MarketClock.ET);
                        String label = (h > 12 ? h - 12 : h) + ":00" + (h >= 12 ? " PM" : " AM");
                        if (today(d).intraday.stream().noneMatch(x -> x.time.equals(label))) intradayCall(cut, false);
                    }
                }
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

    public Record compute() {
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

    public static String body(Record r) { return body(r.summary, r.factors); }

    public static String body(String summary, List<MarketBias.Factor> factors) {
        StringBuilder b = new StringBuilder(summary).append('\n');
        for (MarketBias.Factor f : factors) {
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
