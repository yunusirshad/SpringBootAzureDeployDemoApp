package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * The top-down trading plan ("power of time frames"), long only, intraday.
 *
 * <ol>
 *   <li><b>Daily: direction.</b> Strong Uptrend or Early Bullish Reversal only ({@link TrendClassifier}).</li>
 *   <li><b>Daily / 1h: liquidity.</b> Targets are the resting liquidity above: the prior day's high, the highest high
 *       of the last 20 hourly candles, and the lower edge of the nearest bearish FVG or order block above (1h, 15m).
 *       If the first of these is closer than 2R the trade is skipped: there is supply in the way.</li>
 *   <li><b>1h / 15m: setup.</b> <i>Retest</i>: in the last three 15-minute candles price dipped into an unmitigated
 *       bullish FVG or order block (15m or 1h) and the last 15m close is still above the zone's low.
 *       <i>Breakout</i>: the last 15m candle closed above the prior day's high for the first time, on at least 1.5x
 *       its 20-candle average volume.</li>
 *   <li><b>5m: confirmation and entry.</b> One of the last three 5m candles is bullish and closes above its 20 EMA
 *       and above the previous candle's high. Entry is the next 5m open. The stop is just under the zone (or the
 *       breakout candle) or the last six 5m lows, whichever is lower, less 0.1 ATR(15m); never more than 3% away.</li>
 * </ol>
 *
 * <p><b>More setups</b> (checked after the FVG/OB retest, before the breakout):
 * <ul>
 *   <li><b>FIB</b> (golden zone): on the 1h chart, the swing low to the highest high of the last 40 hours, a leg of at
 *       least 2 ATR(1h) (a trend, not a range). Price pulled back into the 0.5-0.618 retracement in the last three 15m
 *       candles and no 15m candle since the high has closed below 0.786 (a close below 0.786 kills the setup). The stop
 *       goes under 0.786.</li>
 *   <li><b>REVERSAL</b>: in the last six 15m candles one swept the lowest low of the 20 before it (or the prior day's
 *       low) and closed back above it, and the last 15m close is above that sweep candle's high (the structure turned).
 *       The stop goes under the sweep's low.</li>
 * </ul>
 *
 * <p><b>Grade</b>: one point each for the 1h trend (close above a rising 20 EMA), a zone from the 1h chart, an FVG and
 * order block overlapping, an entry inside the Fibonacci golden zone, SPY's 15m close above its 20 EMA, confirmation
 * volume at 1.2x the 5m average or more, and reward to risk of 3 or better. 4+ is A, 2-3 is B, else C.
 *
 * <p><b>Management and exits</b> (day trade): once the trade is up 1R the stop moves to the entry (breakeven). It
 * exits on the stop (filled at the stop, or the open on a gap through it), the target, or the close of the 3:40 PM
 * candle. When a candle reaches both the stop and the target, the stop is assumed (cautious).
 */
public final class TradePlan {

    public static class Settings {
        public double minRR = 2.0;
        public double maxStopPct = 3.0;
        /** Alerts only for setups whose grade has won at least this often in the backtest... */
        public double minWinRate = 60;
        /** ...over at least this many trades. */
        public int minTrades = 15;
        public double costBps = 5;

        public void validate() {
            if (!(minRR >= 1 && minRR <= 10)) throw new IllegalArgumentException("Minimum R:R must be 1 to 10");
            if (!(maxStopPct > 0 && maxStopPct <= 15)) throw new IllegalArgumentException("Largest stop must be above 0% and at most 15%");
            if (!(minWinRate >= 0 && minWinRate <= 100)) throw new IllegalArgumentException("Win rate must be 0 to 100");
            if (minTrades < 1 || minTrades > 500) throw new IllegalArgumentException("Minimum trades must be 1 to 500");
            if (!(costBps >= 0 && costBps <= 100)) throw new IllegalArgumentException("Cost must be 0 to 100 bps");
        }
    }

    /** Bars for one stock (oldest first) plus SPY's 15m bars. */
    public record Bars(List<Bar> d1, List<Bar> h1, List<Bar> m15, List<Bar> m5, List<Bar> spy15) {}

    public record Setup(String type, String grade, int points, List<String> confluence, long signalTime, double entry,
                        double stop, double target, double rr, String targetSource, SmcZones.Zone zone, String trendClass) {
        public String key() { return type + "-" + grade; }
    }

    /** Why there is no setup, for the "watch" list. */
    public record Look(Setup setup, String watch, String reason) {}

    public record Trade(String symbol, String type, String grade, long entryTime, double entry, double stop, double target,
                        long exitTime, double exit, String exitReason, double rMultiple, double returnPct) {
        public boolean win() { return returnPct > 0; }
    }

    static final LocalTime LAST_ENTRY = LocalTime.of(15, 30), EOD_BAR = LocalTime.of(15, 40);

    private TradePlan() {}

    // ---- the setup at a moment ----------------------------------------------------------------------

    /**
     * The plan's read at {@code t} (epoch seconds) from bars that finished by then.
     *
     * @param callWall today's call wall, or NaN (live only; the backtest has no history of it)
     */
    public static Look evaluate(Bars b, long t, Settings s, double callWall) {
        LocalDate day = date(t);
        // 1. daily direction
        List<Bar> daily = new ArrayList<>();
        for (Bar x : b.d1()) if (date(x.time()).isBefore(day)) daily.add(x);
        if (daily.size() < 200) return new Look(null, null, "Fewer than 200 daily candles");
        TrendClassifier.Result tc = TrendClassifier.classify(Indicators.closes(daily));
        if (!TrendClassifier.STRONG.equals(tc.trendClass()) && !TrendClassifier.BULL_REV.equals(tc.trendClass())) {
            return new Look(null, null, "Daily trend is not up (" + tc.trendClass() + ")");
        }
        Bar prevDay = daily.get(daily.size() - 1);

        List<Bar> h1 = upTo(b.h1(), t, 3600, 300), m15 = upTo(b.m15(), t, 900, 300), m5 = upTo(b.m5(), t, 300, 300);
        if (h1.size() < 30 || m15.size() < 30 || m5.size() < 25) return new Look(null, null, "Not enough intraday candles");
        double atr15 = Indicators.atr(m15, 14), atr1h = Indicators.atr(h1, 14);
        if (!(atr15 > 0)) return new Look(null, null, "No 15m ATR");

        // 1h trend
        double[] hc = Indicators.closes(h1);
        double[] he = Indicators.emaSeries(hc, 20);
        boolean h1Up = hc[hc.length - 1] > he[he.length - 1] && he[he.length - 1] > he[he.length - 4];

        // zones
        List<SmcZones.Zone> z15 = SmcZones.active(m15, atr15, 60, "15m"), z1h = SmcZones.active(h1, atr1h, 60, "1h");
        List<SmcZones.Zone> all = new ArrayList<>(z15);
        all.addAll(z1h);

        // 2. setup on 15m
        int n15 = m15.size();
        Bar last15 = m15.get(n15 - 1);
        String type = null;
        SmcZones.Zone zone = null;
        double structureLow = Double.NaN;
        SmcZones.Zone best = null;
        for (SmcZones.Zone z : all) {
            if (!z.bullish() || z.time() >= m15.get(n15 - 3).time()) continue;   // formed before the retest
            boolean touched = false;
            for (int k = n15 - 3; k < n15; k++) if (m15.get(k).low() <= z.high()) touched = true;
            if (touched && last15.close() > z.low() && (best == null || z.high() > best.high())) best = z;
        }
        Fib fib = fib(h1, m15, atr1h);
        if (best != null) {
            type = "RETEST";
            zone = best;
            structureLow = best.low();
        } else if (fib != null && fib.inZone()) {
            type = "FIB";
            structureLow = fib.level(0.786);
        } else if (reversalLow(m15, prevDay) != null) {
            type = "REVERSAL";
            structureLow = reversalLow(m15, prevDay);
        } else {
            double vAvg = 0;
            for (int k = n15 - 21; k < n15 - 1; k++) vAvg += m15.get(k).volume();
            vAvg /= 20;
            Bar prev15 = m15.get(n15 - 2);
            if (date(last15.time()).equals(day) && last15.close() > prevDay.high() && prev15.close() <= prevDay.high()
                    && vAvg > 0 && last15.volume() >= 1.5 * vAvg) {
                type = "BREAKOUT";
                structureLow = last15.low();
            }
        }
        if (type == null) {
            String watch = null;
            for (SmcZones.Zone z : all) {
                if (!z.bullish() || z.high() >= last15.close()) continue;
                double away = (last15.close() / z.high() - 1) * 100;
                if (away <= 2 && (watch == null)) watch = String.format("%.1f%% above a %s %.2f-%.2f", away, z.label(), z.low(), z.high());
            }
            return new Look(null, watch, "No retest or breakout on 15m");
        }

        // 3. 5m confirmation
        double[] c5 = Indicators.closes(m5);
        double[] e5 = Indicators.emaSeries(c5, 20);
        int n5 = m5.size(), conf = -1;
        for (int k = n5 - 1; k >= n5 - 3; k--) {
            Bar x = m5.get(k), p = m5.get(k - 1);
            if (date(x.time()).equals(day) && x.close() > x.open() && x.close() > e5[k] && x.close() > p.high()) { conf = k; break; }
        }
        String zoneText = zone != null ? zone.label() + " " + fmt(zone.low()) + "-" + fmt(zone.high())
                : type.equals("FIB") ? String.format("Fibonacci golden zone %.2f-%.2f (dead below %.2f)", fib.level(0.618), fib.level(0.5), fib.level(0.786))
                : type.equals("REVERSAL") ? String.format("a sweep of %.2f that turned up", structureLow)
                : "prior day high " + fmt(prevDay.high());
        if (conf < 0) {
            String what = switch (type) { case "RETEST" -> "Retesting "; case "FIB" -> "In the "; case "REVERSAL" -> "Reversal after "; default -> "Breaking out: "; };
            return new Look(null, what + zoneText + ", waiting for a 5m confirmation", "No 5m confirmation yet");
        }
        Bar cb = m5.get(conf);

        // 4. entry, stop, target
        double entry = cb.close();
        double swing = Double.POSITIVE_INFINITY;
        for (int k = Math.max(0, n5 - 6); k < n5; k++) swing = Math.min(swing, m5.get(k).low());
        double stop = type.equals("FIB") ? structureLow - 0.1 * atr15 : Math.min(structureLow, swing) - 0.1 * atr15;
        double risk = entry - stop;
        if (!(risk > 0)) return new Look(null, null, "No room for a stop");
        if (risk / entry * 100 > s.maxStopPct) return new Look(null, zoneText, String.format("Stop %.1f%% away (over %.1f%%)", risk / entry * 100, s.maxStopPct));

        TreeMap<Double, String> above = new TreeMap<>();
        if (prevDay.high() > entry) above.put(prevDay.high(), "prior day high");
        double hh = Double.NEGATIVE_INFINITY;
        for (int k = Math.max(0, h1.size() - 20); k < h1.size(); k++) hh = Math.max(hh, h1.get(k).high());
        if (hh > entry) above.putIfAbsent(hh, "20-hour high");
        for (SmcZones.Zone z : all) if (!z.bullish() && z.low() > entry) above.putIfAbsent(z.low(), z.label());
        if (!Double.isNaN(callWall) && callWall > entry) above.putIfAbsent(callWall, "call wall");
        double target;
        String targetSource;
        if (above.isEmpty()) { target = entry + s.minRR * risk; targetSource = fmt(s.minRR) + "R (no liquidity above)"; }
        else {
            var first = above.firstEntry();
            if (first.getKey() < entry + s.minRR * risk) {
                return new Look(null, zoneText, String.format("Liquidity at %.2f (%s) is closer than %.1fR", first.getKey(), first.getValue(), s.minRR));
            }
            target = first.getKey();
            targetSource = first.getValue();
        }
        double rr = (target - entry) / risk;

        // 5. grade
        List<String> conf5 = new ArrayList<>();
        int pts = 0;
        if (h1Up) { pts++; conf5.add("1h trend up"); }
        if (zone != null && zone.timeframe().equals("1h")) { pts++; conf5.add("1h zone"); }
        if (zone != null) {
            for (SmcZones.Zone o : all) {
                if (o.bullish() && o.kind() != zone.kind() && o.overlaps(zone)) { pts++; conf5.add("FVG and OB overlap"); break; }
            }
        }
        if (fib != null && (type.equals("FIB") || (zone != null && zone.low() <= fib.level(0.5) && zone.high() >= fib.level(0.618)))) {
            pts++;
            conf5.add("Fibonacci golden zone");
        }
        List<Bar> spy = upTo(b.spy15(), t, 900, 60);
        if (spy.size() >= 21) {
            double[] sc = Indicators.closes(spy);
            if (sc[sc.length - 1] > Indicators.ema(sc, 20)) { pts++; conf5.add("SPY above its 15m 20 EMA"); }
        }
        double v5 = 0;
        for (int k = conf - 20; k < conf; k++) v5 += m5.get(k).volume();
        v5 /= 20;
        if (v5 > 0 && cb.volume() >= 1.2 * v5) { pts++; conf5.add("volume on the confirmation"); }
        if (rr >= 3) { pts++; conf5.add("R:R 3+"); }
        String grade = pts >= 4 ? "A" : pts >= 2 ? "B" : "C";
        conf5.add(0, tc.trendClass().equals(TrendClassifier.STRONG) ? "daily Strong Uptrend" : "daily Early Bullish Reversal");
        return new Look(new Setup(type, grade, pts, conf5, cb.time(), entry, stop, target, rr, targetSource, zone, tc.trendClass()),
                null, null);
    }

    /** A 1h swing from {@code low} up to {@code high}; {@code inZone}: the last three 15m lows reached the golden zone. */
    record Fib(double low, double high, boolean inZone) {
        double level(double r) { return high - r * (high - low); }
    }

    /**
     * The 1h swing low to the highest high of the last 40 hours, when the leg is at least 2 ATR(1h), the high is at
     * least two hours old (a pullback has started) and no 15m candle since the high has closed below 0.786.
     */
    static Fib fib(List<Bar> h1, List<Bar> m15, double atr1h) {
        int n = h1.size();
        if (n < 45 || !(atr1h > 0)) return null;
        int hi = n - 40;
        for (int k = n - 40; k < n; k++) if (h1.get(k).high() >= h1.get(hi).high()) hi = k;
        if (hi > n - 3) return null;
        int lo = Math.max(0, hi - 40);
        for (int k = Math.max(0, hi - 40); k < hi; k++) if (h1.get(k).low() <= h1.get(lo).low()) lo = k;
        double high = h1.get(hi).high(), low = h1.get(lo).low();
        if (high - low < 2 * atr1h) return null;
        Fib f = new Fib(low, high, false);
        long since = h1.get(hi).time() + 3600;
        for (Bar x : m15) if (x.time() >= since && x.close() < f.level(0.786)) return null;   // setup dead
        int n15 = m15.size();
        boolean touched = false;
        for (int k = Math.max(0, n15 - 3); k < n15; k++) if (m15.get(k).low() <= f.level(0.5)) touched = true;
        boolean held = n15 > 0 && m15.get(n15 - 1).close() >= f.level(0.786);
        return new Fib(low, high, touched && held);
    }

    /**
     * A liquidity sweep that turned up: in the last six 15m candles one went under the lowest low of the 20 before it
     * (or under the prior day's low) and closed back above it, and the last close is above that candle's high.
     * Returns the sweep's low (the stop goes under it), or null.
     */
    static Double reversalLow(List<Bar> m15, Bar prevDay) {
        int n = m15.size();
        if (n < 27) return null;
        double lastClose = m15.get(n - 1).close();
        for (int k = n - 6; k < n - 1; k++) {
            Bar sw = m15.get(k);
            double prior = Double.POSITIVE_INFINITY;
            for (int j = k - 20; j < k; j++) prior = Math.min(prior, m15.get(j).low());
            boolean swept = (sw.low() < prior && sw.close() > prior) || (sw.low() < prevDay.low() && sw.close() > prevDay.low());
            if (swept && lastClose > sw.high()) return sw.low();
        }
        return null;
    }

    // ---- backtest -----------------------------------------------------------------------------------

    /**
     * Replays the plan every 15 minutes from 9:45 AM to 3:30 PM ET over the days in the 5m data: at most one trade per
     * stock per day, entered at the next 5m open, exited on the stop, the target or the 3:40 PM candle.
     */
    public static List<Trade> backtest(String symbol, Bars b, Settings s) {
        List<Trade> out = new ArrayList<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        for (Bar x : b.m5()) days.add(date(x.time()));
        double cost = s.costBps / 10_000;
        for (LocalDate d : days) {
            for (LocalTime slot = LocalTime.of(9, 45); !slot.isAfter(LAST_ENTRY); slot = slot.plusMinutes(15)) {
                long t = d.atTime(slot).atZone(MarketClock.ET).toEpochSecond();
                Look l = evaluate(b, t, s, Double.NaN);
                if (l.setup() == null) continue;
                Trade tr = simulate(symbol, l.setup(), b.m5(), t, cost);
                if (tr != null) out.add(tr);
                break;   // one trade per stock per day
            }
        }
        return out;
    }

    /** Enters at the first 5m open at or after {@code t} and walks the day's 5m candles to the exit. */
    static Trade simulate(String symbol, Setup st, List<Bar> m5, long t, double cost) { return simulate(symbol, st, m5, t, cost, true); }

    /** {@code manage}: move the stop to breakeven once the trade is up 1R. */
    static Trade simulate(String symbol, Setup st, List<Bar> m5, long t, double cost, boolean manage) {
        int i = 0;
        while (i < m5.size() && m5.get(i).time() < t) i++;
        if (i >= m5.size() || !date(m5.get(i).time()).equals(date(t))) return null;
        double entry = m5.get(i).open();
        double risk = st.entry() - st.stop();
        double stop = entry - risk, target = entry + (st.target() - st.entry());   // keep the planned distances
        if (!(entry > stop)) return null;
        LocalDate d = date(t);
        long entryTime = m5.get(i).time();
        boolean breakeven = false;
        for (int k = i; k < m5.size(); k++) {
            Bar x = m5.get(k);
            if (!date(x.time()).equals(d)) break;
            ZonedDateTime z = Instant.ofEpochSecond(x.time()).atZone(MarketClock.ET);
            if (x.low() <= stop) return close(symbol, st, entryTime, entry, stop, target, x.time(), Math.min(x.open(), stop), breakeven ? "BREAKEVEN" : "STOP", risk, cost);
            if (x.high() >= target) return close(symbol, st, entryTime, entry, stop, target, x.time(), Math.max(x.open(), target), "TARGET", risk, cost);
            if (!z.toLocalTime().isBefore(EOD_BAR)) return close(symbol, st, entryTime, entry, stop, target, x.time(), x.close(), "END OF DAY", risk, cost);
            if (manage && !breakeven && x.high() >= entry + risk) { stop = entry; breakeven = true; }   // from the next candle
        }
        Bar last = m5.get(m5.size() - 1);
        return date(last.time()).equals(d) ? close(symbol, st, entryTime, entry, stop, target, last.time(), last.close(), "END OF DAY", risk, cost) : null;
    }

    private static Trade close(String symbol, Setup st, long et, double entry, double stop, double target, long xt,
                               double exit, String why, double risk, double cost) {
        double ret = (exit / entry - 1) - 2 * cost;
        return new Trade(symbol, st.type(), st.grade(), et, entry, stop, target, xt, exit, why, (exit - entry) / risk, ret * 100);
    }

    /** Win rate, average R and profit factor by setup key (e.g. RETEST-A), by grade and overall. */
    public static Map<String, Map<String, Object>> stats(List<Trade> trades) {
        Map<String, List<Trade>> by = new TreeMap<>();
        for (Trade t : trades) {
            by.computeIfAbsent(t.type() + "-" + t.grade(), k -> new ArrayList<>()).add(t);
            by.computeIfAbsent("ALL-" + t.grade(), k -> new ArrayList<>()).add(t);
            by.computeIfAbsent("ALL", k -> new ArrayList<>()).add(t);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (var e : by.entrySet()) {
            List<Trade> l = e.getValue();
            long wins = l.stream().filter(Trade::win).count();
            double gain = l.stream().filter(Trade::win).mapToDouble(Trade::returnPct).sum();
            double loss = -l.stream().filter(x -> !x.win()).mapToDouble(Trade::returnPct).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trades", l.size());
            m.put("wins", wins);
            m.put("winRate", Math.round(1000.0 * wins / l.size()) / 10.0);
            m.put("avgR", Math.round(100 * l.stream().mapToDouble(Trade::rMultiple).average().orElse(0)) / 100.0);
            m.put("avgReturnPct", Math.round(100 * l.stream().mapToDouble(Trade::returnPct).average().orElse(0)) / 100.0);
            m.put("profitFactor", loss == 0 ? null : Math.round(100 * gain / loss) / 100.0);
            out.put(e.getKey(), m);
        }
        return out;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** The bars that finished by {@code t}, at most {@code keep} of them. */
    static List<Bar> upTo(List<Bar> bars, long t, long secs, int keep) {
        int lo = 0, hi = bars.size();
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (bars.get(mid).time() + secs <= t) lo = mid + 1; else hi = mid; }
        return bars.subList(Math.max(0, lo - keep), lo);
    }

    static LocalDate date(long epoch) { return Instant.ofEpochSecond(epoch).atZone(MarketClock.ET).toLocalDate(); }

    private static String fmt(double v) { return String.format("%.2f", v); }
}
