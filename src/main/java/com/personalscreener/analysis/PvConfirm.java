package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PV Confirm signals on daily bars, long only, nested under the trend check (uptrend = SMA50 above SMA200).
 *
 * <p><b>Entry rules</b>, checked on a completed daily bar only when that bar is in an uptrend:
 * <ol>
 *   <li>close above its SMA200</li>
 *   <li>SPY's close above SPY's SMA200 (can be switched off)</li>
 *   <li>RSI(2) below 10</li>
 *   <li>volume below its 50-day average (the average includes the bar itself)</li>
 * </ol>
 * All four on a completed bar is a <b>BUY</b>; the trade enters at the next bar's open with the stop at
 * entry - 2.5 x ATR(14) of the signal bar. <b>Exits</b>, checked on each completed bar from the entry bar on, in this
 * order: <b>STOP</b> when the low touches the stop (filled at the stop, or the open when it gaps below);
 * <b>TAKE PROFIT</b> on the first close above the entry; <b>TIME EXIT</b> at the close of the 10th bar held.
 *
 * <p>The trade state is rebuilt by replaying the last {@link #REPLAY_BARS} bars, so a trade opened in an uptrend is
 * still tracked to its exit after the stock flips to a downtrend. No new entry is ever taken in a downtrend.
 */
public final class PvConfirm {

    public static final int REPLAY_BARS = 40;

    public enum Status { READY_TO_BUY, BUY, IN_TRADE, SELL, WAIT, NA_DOWNTREND }

    /** Editable on the settings panel. */
    public static class Settings {
        public int rsiLength = 2;
        public double rsiBuyBelow = 10;
        /** READY TO BUY: the other three rules pass and RSI(2) is under this, but not yet under rsiBuyBelow. */
        public double rsiReadyBelow = 20;
        public int smaLength = 200;
        public int volLength = 50;
        /** Volume must be under this percent of its average. */
        public double volMaxPct = 100;
        public int atrLength = 14;
        public double atrMult = 2.5;
        public int maxBars = 10;
        public boolean spyFilter = true;
        public boolean alerts = true;

        public void validate() {
            check(rsiLength >= 2 && rsiLength <= 14, "RSI length must be 2 to 14");
            check(rsiBuyBelow > 0 && rsiBuyBelow < 50, "RSI buy level must be between 0 and 50");
            check(rsiReadyBelow >= rsiBuyBelow && rsiReadyBelow < 60, "RSI ready level must be at or above the buy level and under 60");
            check(smaLength >= 50 && smaLength <= 250, "SMA length must be 50 to 250");
            check(volLength >= 5 && volLength <= 200, "Volume average length must be 5 to 200");
            check(volMaxPct > 0 && volMaxPct <= 300, "Volume limit must be above 0% and at most 300%");
            check(atrLength >= 2 && atrLength <= 50, "ATR length must be 2 to 50");
            check(atrMult > 0 && atrMult <= 10, "Stop multiple must be above 0 and at most 10");
            check(maxBars >= 1 && maxBars <= 60, "Time exit must be 1 to 60 bars");
        }

        private static void check(boolean ok, String msg) { if (!ok) throw new IllegalArgumentException(msg); }
    }

    public record Rules(boolean closeAboveSma, boolean spyAboveSma, boolean rsiBelow, boolean volBelowAvg) {
        public int passed() { return (closeAboveSma ? 1 : 0) + (spyAboveSma ? 1 : 0) + (rsiBelow ? 1 : 0) + (volBelowAvg ? 1 : 0); }
    }

    public record Trade(LocalDate signalDate, LocalDate entryDate, double entry, double stop, LocalDate exitDate,
                        Double exit, String exitReason, int bars, Double returnPct) {}

    public record Result(Status status, String exitReason, boolean trendUp, Double entry, boolean entryEstimated,
                         Double stop, Double exit, LocalDate entryDate, Integer barsHeld, LocalDate signalDate,
                         double rsi, double volPct, Rules rules, boolean nearSetup, String note,
                         LocalDate barDate, double close, double sma50, double sma200, double atr,
                         List<Trade> trades, Expected expected) {
        Result withExpected(Expected e) {
            return new Result(status, exitReason, trendUp, entry, entryEstimated, stop, exit, entryDate, barsHeld, signalDate,
                    rsi, volPct, rules, nearSetup, note, barDate, close, sma50, sma200, atr, trades, e);
        }
    }

    /**
     * For WAIT and READY TO BUY in an uptrend: the close that would fire the BUY, its stop, and a target.
     * {@code buy} is the highest next close that puts RSI(n) under the buy level (today's close when it already is);
     * {@code stop} is buy - ATR multiple x ATR; {@code target} is the 5-day average including that close (where an
     * RSI(2) dip usually reverts to; buy + 0.5 ATR when that is not above the buy). The trade itself still exits on the
     * first close above the entry. {@code needs} lists what else has to hold.
     */
    public record Expected(double buy, double stop, double target, double dropPct, List<String> needs) {}

    private PvConfirm() {}

    /**
     * @param completed daily bars that are finished, oldest first
     * @param live      today's bar while it is still forming, or null; only its open is used (the entry after a
     *                  BUY on the last completed bar)
     * @param spyAbove  for each date, whether SPY closed above its SMA200
     */
    public static Result evaluate(List<Bar> completed, Bar live, Map<LocalDate, Boolean> spyAbove, Settings s) {
        return evaluate(completed, live, spyAbove, s, REPLAY_BARS, false);
    }

    /** Every PV Confirm trade over the whole history (for the per-stock playbook). */
    public static List<Trade> backtest(List<Bar> completed, Map<LocalDate, Boolean> spyAbove, Settings s) {
        return evaluate(completed, null, spyAbove, s, Integer.MAX_VALUE / 2, true).trades();
    }

    private static Result evaluate(List<Bar> completed, Bar live, Map<LocalDate, Boolean> spyAbove, Settings s, int replay, boolean allTrades) {
        int n = completed.size();
        if (n < Math.max(s.smaLength, 200) + 2) {
            return empty(n == 0 ? null : date(completed.get(n - 1)), "Only " + n + " daily bars; " + (Math.max(s.smaLength, 200) + 2) + " needed");
        }
        double[] c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) { c[i] = completed.get(i).close(); v[i] = completed.get(i).volume(); }
        double[] sma50 = smaSeries(c, 50), sma200 = smaSeries(c, 200), smaRule = smaSeries(c, s.smaLength);
        double[] volAvg = smaSeries(v, s.volLength);
        double[] rsi = rsiSeries(c, s.rsiLength);
        double[] atr = QuantumShift.atrSeries(completed, s.atrLength);

        List<Trade> trades = new ArrayList<>();
        int last = n - 1, start = Math.max(Math.max(s.smaLength, 200), last - replay);
        boolean inTrade = false;
        int entryIdx = -1, pending = -1;
        double entry = 0, stop = 0;
        LocalDate signalDate = null;
        Trade exitedOnLast = null;

        for (int i = start; i <= last; i++) {
            Bar b = completed.get(i);
            if (pending >= 0) {                       // enter at this bar's open
                inTrade = true;
                entryIdx = i;
                entry = b.open();
                stop = entry - s.atrMult * atr[pending];
                signalDate = date(completed.get(pending));
                pending = -1;
            }
            if (inTrade) {
                int held = i - entryIdx + 1;
                Double exit = null;
                String why = null;
                if (b.low() <= stop) { exit = Math.min(b.open(), stop); why = "STOP"; }
                else if (b.close() > entry) { exit = b.close(); why = "TAKE PROFIT"; }
                else if (held >= s.maxBars) { exit = b.close(); why = "TIME EXIT"; }
                if (exit != null) {
                    Trade t = new Trade(signalDate, date(completed.get(entryIdx)), entry, stop, date(b), exit, why, held,
                            (exit / entry - 1) * 100);
                    trades.add(t);
                    inTrade = false;
                    if (i == last) exitedOnLast = t;
                }
            }
            if (!inTrade && pending < 0 && trendUp(sma50, sma200, i) && rules(i, c, smaRule, rsi, v, volAvg, spyAbove, completed, s).passed() == 4) {
                pending = i;
            }
        }

        Rules r = rules(last, c, smaRule, rsi, v, volAvg, spyAbove, completed, s);
        boolean up = trendUp(sma50, sma200, last);
        double volPct = volAvg[last] > 0 ? v[last] / volAvg[last] * 100 : Double.NaN;
        LocalDate barDate = date(completed.get(last));
        List<Trade> recent = allTrades || trades.size() <= 5 ? trades : trades.subList(trades.size() - 5, trades.size());

        // 1. a BUY on the last completed bar that has already filled at today's open
        if (pending == last && live != null) {
            double atrSig = atr[last], e = live.open(), st = e - s.atrMult * atrSig;
            return new Result(Status.IN_TRADE, null, up, e, false, st, null, date(live), 1, barDate, rsi[last], volPct,
                    r, false, "Entered at today's open on the BUY from " + barDate, barDate, c[last], sma50[last],
                    sma200[last], atrSig, recent, null);
        }
        // 2. an exit on the last completed bar comes before a new BUY on the same bar: get out first
        if (exitedOnLast != null) {
            Trade t = exitedOnLast;
            return new Result(Status.SELL, t.exitReason(), up, t.entry(), false, t.stop(), t.exit(), t.entryDate(), t.bars(),
                    t.signalDate(), rsi[last], volPct, r, false,
                    t.exitReason() + " on " + t.exitDate() + String.format(" · %+.2f%%", t.returnPct())
                            + (pending == last ? ". A new BUY also fired on this bar: re-enter at the next open" : ""),
                    barDate, c[last], sma50[last], sma200[last], atr[last], recent, null);
        }
        // 3. a BUY waiting for the next open
        if (pending == last) {
            double atrSig = atr[last], est = c[last];
            return new Result(Status.BUY, null, up, est, true, est - s.atrMult * atrSig, null, null, null, barDate,
                    rsi[last], volPct, r, false, "All 4 rules passed on " + barDate + ". Enter at the next open; entry and stop are estimated from the close.",
                    barDate, c[last], sma50[last], sma200[last], atrSig, recent, null);
        }
        // 4. an open trade
        if (inTrade) {
            int held = last - entryIdx + 1;
            String note = "Held " + held + " of " + s.maxBars + " bars" + (up ? "" : ". Trend flipped to downtrend; tracking the exit only");
            if (live != null && live.low() <= stop) note += ". Stop touched today; confirms on the close";
            return new Result(Status.IN_TRADE, null, up, entry, false, stop, null, date(completed.get(entryIdx)), held, signalDate,
                    rsi[last], volPct, r, false, note, barDate, c[last], sma50[last], sma200[last], atr[last], recent, null);
        }
        if (!up) {
            return new Result(Status.NA_DOWNTREND, null, false, null, false, null, null, null, null, null, rsi[last], volPct,
                    r, false, "Downtrend (SMA50 at or below SMA200): no new entries", barDate, c[last], sma50[last],
                    sma200[last], atr[last], recent, null);
        }
        boolean othersPass = r.closeAboveSma() && r.spyAboveSma() && r.volBelowAvg();
        boolean ready = othersPass && !r.rsiBelow() && rsi[last] < s.rsiReadyBelow;
        boolean near = r.passed() >= 3;
        String note = ready ? "RSI(" + s.rsiLength + ") " + Math.round(rsi[last]) + ": one more down day can trigger the BUY"
                : "Missing: " + missing(r, s);
        return new Result(ready ? Status.READY_TO_BUY : Status.WAIT, null, true, null, false, null, null, null, null, null,
                rsi[last], volPct, r, near, note, barDate, c[last], sma50[last], sma200[last], atr[last], recent, null)
                .withExpected(expected(c, s, r, atr[last]));
    }

    /** The close that would fire the BUY on the next bar, with its stop and target. */
    static Expected expected(double[] c, Settings s, Rules r, double atr) {
        int n = c.length, len = s.rsiLength;
        double[] gl = wilder(c, len);
        double g = gl[0], l = gl[1], close = c[n - 1], t = s.rsiBuyBelow;
        // next bar with a drop d: g' = g(len-1)/len, l' = (l(len-1) + d)/len; RSI' < t  <=>  l' > g'(100-t)/t
        double gNext = g * (len - 1) / len;
        double need = len * gNext * (100 - t) / t - l * (len - 1);
        double drop = r.rsiBelow() ? 0 : Math.max(0, need) + 1e-4 * close;
        double buy = Math.max(0.01, close - drop);
        double stop = buy - s.atrMult * atr;
        double sum4 = 0;
        for (int i = n - 4; i < n; i++) sum4 += c[i];
        double target = (sum4 + buy) / 5;
        if (!(target > buy)) target = buy + 0.5 * atr;

        List<String> needs = new ArrayList<>();
        double sumSma = 0;
        for (int i = n - s.smaLength + 1; i < n; i++) sumSma += c[i];
        double smaNext = (sumSma + buy) / s.smaLength;
        if (buy <= smaNext) needs.add("a dip that deep would close under SMA" + s.smaLength + " (" + Math.round(smaNext * 100) / 100.0 + ")");
        if (!r.spyAboveSma()) needs.add("SPY back above its SMA200");
        needs.add("volume under " + fmt(s.volMaxPct) + "% of its " + s.volLength + "-day average that day");
        return new Expected(round(buy), round(stop), round(target), round(drop / close * 100), needs);
    }

    /** Wilder average gain and loss at the last bar. */
    static double[] wilder(double[] c, int n) {
        double gain = 0, loss = 0;
        for (int i = 1; i <= n && i < c.length; i++) {
            double d = c[i] - c[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= n;
        loss /= n;
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
        }
        return new double[]{gain, loss};
    }

    private static double round(double v) { return Math.round(v * 10_000) / 10_000.0; }

    static String missing(Rules r, Settings s) {
        List<String> m = new ArrayList<>();
        if (!r.closeAboveSma()) m.add("close above SMA" + s.smaLength);
        if (!r.spyAboveSma()) m.add("SPY above its SMA200");
        if (!r.rsiBelow()) m.add("RSI(" + s.rsiLength + ") under " + fmt(s.rsiBuyBelow));
        if (!r.volBelowAvg()) m.add("volume under " + fmt(s.volMaxPct) + "% of its " + s.volLength + "-day average");
        return m.isEmpty() ? "nothing" : String.join(", ", m);
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    private static Rules rules(int i, double[] c, double[] sma, double[] rsi, double[] v, double[] volAvg,
                               Map<LocalDate, Boolean> spyAbove, List<Bar> bars, Settings s) {
        boolean spy = !s.spyFilter || Boolean.TRUE.equals(spyAbove.get(date(bars.get(i))));
        return new Rules(!Double.isNaN(sma[i]) && c[i] > sma[i], spy,
                !Double.isNaN(rsi[i]) && rsi[i] < s.rsiBuyBelow,
                volAvg[i] > 0 && v[i] / volAvg[i] * 100 < s.volMaxPct);
    }

    private static boolean trendUp(double[] sma50, double[] sma200, int i) {
        return !Double.isNaN(sma50[i]) && !Double.isNaN(sma200[i]) && sma50[i] > sma200[i];
    }

    private static Result empty(LocalDate d, String note) {
        return new Result(Status.WAIT, null, false, null, false, null, null, null, null, null, Double.NaN, Double.NaN,
                new Rules(false, false, false, false), false, note, d, Double.NaN, Double.NaN, Double.NaN, Double.NaN, List.of(), null);
    }

    /** SPY above its SMA200, by date. */
    public static Map<LocalDate, Boolean> spyAbove(List<Bar> spy) {
        double[] c = new double[spy.size()];
        for (int i = 0; i < c.length; i++) c[i] = spy.get(i).close();
        double[] sma = smaSeries(c, 200);
        Map<LocalDate, Boolean> m = new java.util.HashMap<>();
        for (int i = 0; i < c.length; i++) if (!Double.isNaN(sma[i])) m.put(date(spy.get(i)), c[i] > sma[i]);
        return m;
    }

    public static LocalDate date(Bar b) { return Instant.ofEpochSecond(b.time()).atZone(MarketClock.ET).toLocalDate(); }

    static double[] smaSeries(double[] x, int n) {
        double[] out = new double[x.length];
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            sum += x[i];
            if (i >= n) sum -= x[i - n];
            out[i] = i >= n - 1 ? sum / n : Double.NaN;
        }
        return out;
    }

    /** Wilder RSI series. */
    static double[] rsiSeries(double[] c, int n) {
        double[] out = new double[c.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (c.length <= n) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = c[i] - c[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= n;
        loss /= n;
        out[n] = rsi(gain, loss);
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
            out[i] = rsi(gain, loss);
        }
        return out;
    }

    private static double rsi(double g, double l) { return l == 0 ? (g == 0 ? 50 : 100) : 100 - 100 / (1 + g / l); }
}
