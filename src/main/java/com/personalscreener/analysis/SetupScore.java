package com.personalscreener.analysis;

import com.personalscreener.model.GexLevels;
import com.personalscreener.model.Num;
import com.personalscreener.model.StockRow;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Low risk, high reward: the score the Core list is sorted by, 0-100, plus the Buy now / Ready to buy signal.
 *
 * <ul>
 *   <li><b>Range meter (25)</b>: room left in today's ATR. 0% used scores 25; 100% or more scores 0.</li>
 *   <li><b>Reward : risk (25)</b>. Risk is the price down to support, the higher of today's low and the put wall
 *       below the price (at least 0.25 ATR). Reward is the price up to the target, today's low + 1 ATR, capped at the
 *       call wall when the wall is lower and above the price. 3:1 or better scores 25.</li>
 *   <li><b>Session phase (20)</b>: Bull Distribution 20, Bull Absorption 16, Bear Absorption 12, Bull Manipulation
 *       10, Bull Accumulation 8, times that phase's percent.</li>
 *   <li><b>Long score (30)</b>: {@link LongScore} x 0.3.</li>
 * </ul>
 *
 * <p>The <b>super stock</b> is the highest score among Buy now names, or among Ready to buy names when none is
 * a Buy now.
 */
public final class SetupScore {

    public record Result(int score, Double rewardRisk, Double stop, Double target, Double riskPct, Double rewardPct,
                         List<String> parts) {}

    private SetupScore() {}

    public static Result of(StockRow r) {
        List<String> parts = new ArrayList<>();
        double p = Num.d(r.price), atr = Num.d(r.atr);
        double s = 0;

        // 1. range meter
        double used = Num.d(r.rangeUsedPct);
        double range = Double.isNaN(used) ? 12.5 : 25 * clamp(1 - used / 100);
        s += range;
        parts.add("range " + Math.round(range));

        // 2. reward : risk
        Double rr = null, stop = null, target = null, riskPct = null, rewardPct = null;
        if (p > 0 && atr > 0) {
            double low = Num.d(r.dayLow);
            if (Double.isNaN(low) || low > p) low = p - 0.5 * atr;
            GexLevels g = r.gex;
            double support = low;
            if (g != null && !Double.isNaN(g.putWall()) && g.putWall() < p) support = Math.max(support, g.putWall());
            double risk = Math.max(p - support, 0.25 * atr);
            double tgt = low + atr;
            if (g != null && !Double.isNaN(g.callWall()) && g.callWall() > p) tgt = Math.min(tgt, g.callWall());
            double reward = Math.max(0, tgt - p);
            rr = Num.round(reward / risk, 2);
            stop = Num.round(p - risk, 4);
            target = Num.round(tgt, 4);
            riskPct = Num.round(risk / p * 100, 2);
            rewardPct = Num.round(reward / p * 100, 2);
            double pts = 25 * clamp(rr / 3);
            s += pts;
            parts.add("R:R " + Math.round(pts));
        }

        // 3. session phase
        double phase = switch (r.sessionPhase == null ? "" : r.sessionPhase) {
            case "Bull Distribution" -> 20;
            case "Bull Absorption" -> 16;
            case "Bear Absorption" -> 12;
            case "Bull Manipulation" -> 10;
            case "Bull Accumulation" -> 8;
            default -> 0;
        } * (r.sessionPct == null ? 0 : r.sessionPct / 100.0);
        s += phase;
        if (phase > 0) parts.add("phase " + Math.round(phase));

        // 4. trend and the rest
        double ls = 0.3 * r.longScore;
        s += ls;
        parts.add("long " + Math.round(ls));

        return new Result((int) Math.round(Math.max(0, Math.min(100, s))), rr, stop, target, riskPct, rewardPct, parts);
    }

    public static final String BUY_NOW = "BUY_NOW", READY = "READY", HOLD = "HOLD", READY_SELL = "READY_SELL",
            SELL_NOW = "SELL_NOW", WAIT = "WAIT";

    public record Signal(String signal, List<String> notes) {}

    /** Share of a normal day's volume traded by minutes since the 9:30 ET open (volume runs heavy at both ends). */
    private static final double[][] VOLUME_CURVE = {{0, 0}, {15, 0.10}, {30, 0.16}, {60, 0.25}, {120, 0.38},
            {180, 0.48}, {240, 0.57}, {300, 0.67}, {360, 0.80}, {390, 1.0}};

    /**
     * RVOL paced to the time of day: today's volume so far against the share of a normal day that has usually traded
     * by now. Raw RVOL (volume / 10-day average) reads low all morning because the day is not over; at 10:30 a stock
     * on twice its normal pace shows only about 0.5. The first 15 minutes count as 15 so one early print can't
     * inflate it. Before the open, on weekends and after the close it is the raw RVOL.
     */
    public static Double pacedRelVol(Double relVol, ZonedDateTime etNow) {
        if (relVol == null || Double.isNaN(relVol)) return null;
        double share = 1;
        DayOfWeek d = etNow.getDayOfWeek();
        if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
            double m = (etNow.toLocalTime().toSecondOfDay() - 9.5 * 3600) / 60.0;
            if (m >= 0 && m < 390) share = curve(Math.max(15, m));
        }
        return Num.round(relVol / share, 2);
    }

    /** Buy now needs paced RVOL of 1.5 in the first hour (9:30 to 10:30 ET) and 2.0 after. */
    public static double rvolNeed(ZonedDateTime etNow) {
        LocalTime t = etNow.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(10, 30)) ? 1.5 : 2.0;
    }

    private static double curve(double m) {
        for (int i = 1; i < VOLUME_CURVE.length; i++) {
            if (m <= VOLUME_CURVE[i][0]) {
                double[] a = VOLUME_CURVE[i - 1], b = VOLUME_CURVE[i];
                return a[1] + (b[1] - a[1]) * (m - a[0]) / (b[0] - a[0]);
            }
        }
        return 1;
    }

    /**
     * For a Wait: the expected buy at the breakout level (the premarket or prior-day high; the price when it is
     * already through), the stop at support (today's low or the put wall, at least 0.25 ATR under the buy), and the
     * target at buy + 1 ATR, capped at the call wall above the buy. Null without a price and ATR.
     */
    public static double[] expected(StockRow r) {
        double p = Num.d(r.price), atr = Num.d(r.atr);
        if (!(p > 0) || !(atr > 0)) return null;
        double bo = Num.d(r.breakoutLevel);
        double buy = !Double.isNaN(bo) && bo > p ? bo : p;
        double low = Num.d(r.dayLow);
        double support = Double.isNaN(low) || low >= buy ? buy - 0.5 * atr : low;
        GexLevels g = r.gex;
        if (g != null && !Double.isNaN(g.putWall()) && g.putWall() < buy) support = Math.max(support, g.putWall());
        double stop = Math.min(support, buy - 0.25 * atr);
        double target = buy + atr;
        if (g != null && !Double.isNaN(g.callWall()) && g.callWall() > buy) target = Math.min(target, g.callWall());
        return new double[]{Num.round(buy, 4), Num.round(stop, 4), Num.round(target, 4)};
    }

    /** Strong Uptrend or Early Bullish Reversal. */
    public static boolean longTrend(StockRow r) {
        return TrendClassifier.STRONG.equals(r.trendClass) || TrendClassifier.BULL_REV.equals(r.trendClass);
    }

    /** Any trend the Core list shows: an uptrend or either reversal (not No Uptrend). */
    public static boolean listed(StockRow r) {
        return longTrend(r) || TrendClassifier.BEAR_REV.equals(r.trendClass);
    }

    /**
     * One of Buy now, Ready to buy, Hold, Ready to sell, Sell now or Wait. Sell signals are about leaving a long, so
     * they are checked first; the notes say why (for a sell) or what is missing (for Ready to buy and Wait).
     * <ul>
     *   <li><b>Sell now</b>: an Early Bearish Reversal below the 1h 20 EMA with a bias that is not Bullish; or the
     *       price at or above the call wall; or 100% of the ATR range used with the latest session a Bear phase of
     *       60% or more.</li>
     *   <li><b>Ready to sell</b>: an Early Bearish Reversal; or below the 1h 20 EMA with a Bearish bias; or 100% of
     *       the ATR range used; or earnings inside 3 trading days; or a Bear Distribution or Bear Manipulation
     *       session of 70% or more.</li>
     *   <li><b>Buy now</b>: a Strong Uptrend or Early Bullish Reversal, Zoya compliant, through the breakout level
     *       (the premarket or prior-day high), above the 1h 20 EMA, the latest session in a Bull phase, reward to
     *       risk 1.5 or better, under 80% of the range used, a bias that is not Bearish, and paced RVOL of 2x or more
     *       (1.5x in the first hour).</li>
     *   <li><b>Ready to buy</b>: the same trend, compliance and bias, reward to risk 1 or better, and above the 1h
     *       20 EMA or within 3% of the breakout.</li>
     *   <li><b>Hold</b>: a Strong Uptrend or Early Bullish Reversal still intact (above the 1h 20 EMA or a bias that
     *       is not Bearish) that is not a fresh buy.</li>
     *   <li><b>Wait</b>: anything else.</li>
     * </ul>
     */
    public static Signal signal(StockRow r) {
        double p = Num.d(r.price), used = Num.d(r.rangeUsedPct), toBo = Num.d(r.toBreakoutPct), rr = Num.d(r.rewardRisk);
        boolean bearRev = TrendClassifier.BEAR_REV.equals(r.trendClass);
        boolean bearishBias = "Bearish".equals(r.bias), bullishBias = "Bullish".equals(r.bias);
        boolean spent = !Double.isNaN(used) && used >= 100;
        String ph = r.sessionPhase == null ? "" : r.sessionPhase;
        int phPct = r.sessionPct == null ? 0 : r.sessionPct;
        boolean bearSession = ph.startsWith("Bear");
        GexLevels g = r.gex;
        boolean atCallWall = g != null && !Double.isNaN(g.callWall()) && p >= g.callWall();

        // 1. sell now
        List<String> sell = new ArrayList<>();
        if (bearRev && !r.above1hEma && !bullishBias) sell.add("bearish reversal below the 1h 20 EMA");
        if (atCallWall) sell.add("at the call wall (target)");
        if (spent && bearSession && phPct >= 60) sell.add("range used up into a " + phPct + "% " + ph + " session");
        if (!sell.isEmpty()) return new Signal(SELL_NOW, sell);

        // 2. ready to sell
        List<String> warn = new ArrayList<>();
        if (bearRev) warn.add("early bearish reversal");
        if (!r.above1hEma && bearishBias) warn.add("below the 1h 20 EMA with a bearish bias");
        if (spent) warn.add("ATR range used up");
        if (r.earningsSoon) warn.add("earnings inside 3 trading days");
        if ((ph.equals("Bear Distribution") || ph.equals("Bear Manipulation")) && phPct >= 70) warn.add(phPct + "% " + ph);
        if (!warn.isEmpty()) return new Signal(READY_SELL, warn);

        // 3. buy now / ready to buy
        boolean compliant = "COMPLIANT".equals(r.zoya);
        if (longTrend(r) && compliant && !bearishBias) {
            boolean broke = !Double.isNaN(toBo) && toBo <= 0;
            List<String> buy = new ArrayList<>();
            if (!broke) buy.add(Double.isNaN(toBo) ? "no breakout level" : "breakout " + Num.round(toBo, 1) + "% away");
            if (!r.above1hEma) buy.add("below the 1h 20 EMA");
            if (!ph.startsWith("Bull")) buy.add("session not in a Bull phase");
            if (!(rr >= 1.5)) buy.add("R:R under 1.5");
            if (!(Double.isNaN(used) || used < 80)) buy.add("over 80% of the range used");
            double need = r.rvolNeed == null ? 2.0 : r.rvolNeed;
            if (r.rvolPaced == null || r.rvolPaced < need) buy.add(r.rvolPaced == null ? "no volume reading"
                    : String.format("RVOL %.1f× under %.1f× (paced to the time of day)", r.rvolPaced, need));
            if (buy.isEmpty()) return new Signal(BUY_NOW, List.of());
            boolean near = !Double.isNaN(toBo) && toBo <= 3;
            if (rr >= 1 && (r.above1hEma || near)) return new Signal(READY, buy);
        }

        // 4. hold / wait
        if (longTrend(r) && (r.above1hEma || !bearishBias)) {
            List<String> why = new ArrayList<>();
            if (!compliant) why.add("not Zoya compliant, so no buy signal");
            if (!(rr >= 1)) why.add("R:R under 1 for a new entry");
            if (!r.above1hEma) why.add("below the 1h 20 EMA");
            return new Signal(HOLD, why.isEmpty() ? List.of("trend intact, no fresh entry") : why);
        }
        List<String> wait = new ArrayList<>();
        if (!listed(r)) wait.add("no uptrend");
        if (!compliant) wait.add("not Zoya compliant");
        if (wait.isEmpty()) wait.add("no setup");
        return new Signal(WAIT, wait);
    }

    private static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }
}
