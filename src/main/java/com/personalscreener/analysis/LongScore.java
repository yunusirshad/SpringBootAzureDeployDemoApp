package com.personalscreener.analysis;

import com.personalscreener.model.GexLevels;
import com.personalscreener.model.StockRow;
import com.personalscreener.model.Num;

import java.util.ArrayList;
import java.util.List;

/**
 * How good a long a name is right now, 0-100. The Core list is sorted by it, highest first.
 *
 * <table>
 *   <tr><td>Daily trend class</td><td>35</td><td>Strong Uptrend 35, Early Bullish Reversal 25, Early Bearish Reversal 5</td></tr>
 *   <tr><td>Intraday bias</td><td>10</td><td>15m/1h/4h Bullish 10, Mixed 5</td></tr>
 *   <tr><td>Above the 1h 20 EMA</td><td>5</td><td></td></tr>
 *   <tr><td>Daily +DI over -DI (Long side)</td><td>10</td><td></td></tr>
 *   <tr><td>Daily MACD up</td><td>5</td><td></td></tr>
 *   <tr><td>Daily RSI</td><td>5</td><td>50-70: 5; 40-50 or 70-80: 2</td></tr>
 *   <tr><td>Quality</td><td>10</td><td>quality score / 10</td></tr>
 *   <tr><td>Relative volume</td><td>5</td><td>1x or more</td></tr>
 *   <tr><td>Zoya</td><td>5</td><td>compliant</td></tr>
 *   <tr><td>Room to the call wall</td><td>10</td><td>price above the put wall and at least 1 ATR under the call wall: 10; under the call wall: 5</td></tr>
 *   <tr><td>Earnings inside 3 trading days</td><td>-15</td><td></td></tr>
 * </table>
 * Ties go to the wider 50-day-over-200-day gap.
 */
public final class LongScore {

    public record Result(int score, List<String> parts) {}

    private LongScore() {}

    public static Result of(StockRow r) {
        List<String> parts = new ArrayList<>();
        double s = 0;
        int trend = switch (r.trendClass == null ? "" : r.trendClass) {
            case TrendClassifier.STRONG -> 35;
            case TrendClassifier.BULL_REV -> 25;
            case TrendClassifier.BEAR_REV -> 5;
            default -> 0;
        };
        s += add(parts, "trend", trend);
        s += add(parts, "bias", "Bullish".equals(r.bias) ? 10 : "Mixed".equals(r.bias) ? 5 : 0);
        s += add(parts, "1h EMA", r.above1hEma ? 5 : 0);
        s += add(parts, "+DI", "Long".equals(r.side) ? 10 : 0);
        s += add(parts, "MACD", r.macdUp ? 5 : 0);
        double rsi = Num.d(r.rsi);
        s += add(parts, "RSI", rsi >= 50 && rsi <= 70 ? 5 : (rsi >= 40 && rsi < 50) || (rsi > 70 && rsi <= 80) ? 2 : 0);
        s += add(parts, "quality", (int) Math.round(Math.max(0, Math.min(100, r.qualityScore)) / 10.0));
        s += add(parts, "RVol", Num.d(r.relVol) >= 1 ? 5 : 0);
        s += add(parts, "Zoya", "COMPLIANT".equals(r.zoya) ? 5 : 0);
        s += add(parts, "room", room(r));
        if (r.earningsSoon) s += add(parts, "earnings", -15);
        return new Result((int) Math.max(0, Math.min(100, Math.round(s))), parts);
    }

    static int room(StockRow r) {
        GexLevels g = r.gex;
        double p = Num.d(r.price), atr = Num.d(r.atr);
        if (g == null || Double.isNaN(p) || Double.isNaN(g.callWall())) return 0;
        boolean abovePut = Double.isNaN(g.putWall()) || p > g.putWall();
        if (!abovePut || p >= g.callWall()) return 0;
        return !Double.isNaN(atr) && g.callWall() - p >= atr ? 10 : 5;
    }

    private static int add(List<String> parts, String name, int pts) {
        if (pts != 0) parts.add(name + " " + (pts > 0 ? "+" : "") + pts);
        return pts;
    }
}
