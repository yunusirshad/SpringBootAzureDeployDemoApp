package com.personalscreener.analysis;

import java.util.Arrays;

/**
 * Classifies a stock from completed daily closes using price structure only: the 20-day EMA and the 50- and
 * 200-day simple averages. RSI, MACD, ADX, stochastic and the Bollinger position are readings, not inputs here.
 *
 * <ul>
 *   <li><b>Strong Uptrend</b>: the 50-day is above the 200-day and the close holds above both the 50-day and the
 *       20 EMA.</li>
 *   <li><b>Early Bullish Reversal</b>: the 50-day crossed above the 200-day within the last 15 sessions with the
 *       close above the 20 EMA, or the 50-day is still at or below the 200-day but the close has reclaimed the
 *       50-day and a rising 20 EMA.</li>
 *   <li><b>Early Bearish Reversal</b>: the 50-day is above the 200-day, but the close has lost the 50-day, or
 *       sits below a falling 20 EMA.</li>
 *   <li><b>No Uptrend</b>: everything else. Fewer than 200 completed candles means there is no structure to
 *       read, so it stays No Uptrend.</li>
 * </ul>
 */
public final class TrendClassifier {

    public static final String STRONG = "STRONG_UPTREND", BULL_REV = "EARLY_BULLISH_REVERSAL",
            BEAR_REV = "EARLY_BEARISH_REVERSAL", NONE = "NO_UPTREND";
    static final int CROSS_LOOKBACK = 15, SLOPE_LOOKBACK = 5;

    public record Result(String trendClass, String why, double ema20) {}

    private TrendClassifier() {}

    public static Result classify(double[] closes) {
        int n = closes.length;
        if (n < 200) return new Result(NONE, "Only " + n + " completed daily candles; 200 are needed for the structure", Double.NaN);
        double close = closes[n - 1];
        double[] ema = Indicators.emaSeries(closes, 20);
        double ema20 = ema[n - 1], emaBefore = ema[n - 1 - SLOPE_LOOKBACK];
        double sma50 = sma(closes, n, 50), sma200 = sma(closes, n, 200);
        if (Double.isNaN(ema20) || Double.isNaN(sma50) || Double.isNaN(sma200)) {
            return new Result(NONE, "Moving averages not available", ema20);
        }
        boolean emaRising = !Double.isNaN(emaBefore) && ema20 > emaBefore;
        boolean emaFalling = !Double.isNaN(emaBefore) && ema20 < emaBefore;

        if (sma50 > sma200) {
            if (close < sma50) return new Result(BEAR_REV, "Closed below the 50-day while the 50-day is above the 200-day", ema20);
            if (close < ema20 && emaFalling) return new Result(BEAR_REV, "Closed below a falling 20 EMA inside an uptrend", ema20);
            int crossAgo = crossAgo(closes);
            if (crossAgo >= 0 && close > ema20) {
                return new Result(BULL_REV, "50-day crossed above the 200-day " + (crossAgo == 0 ? "today" : crossAgo + " sessions ago"), ema20);
            }
            return new Result(STRONG, "50-day above the 200-day, close above the 50-day and the 20 EMA", ema20);
        }
        if (close > sma50 && close > ema20 && emaRising) {
            return new Result(BULL_REV, "Reclaimed the 50-day and a rising 20 EMA; the 50-day is still under the 200-day", ema20);
        }
        return new Result(NONE, "50-day at or below the 200-day", ema20);
    }

    /** Sessions since the 50-day last crossed above the 200-day, within the lookback; -1 if it did not. */
    static int crossAgo(double[] c) {
        int n = c.length;
        for (int k = 0; k < CROSS_LOOKBACK && n - k - 1 >= 200; k++) {
            int end = n - k;          // the average as of candle end-1
            double f = sma(c, end, 50), s = sma(c, end, 200);
            double fPrev = sma(c, end - 1, 50), sPrev = sma(c, end - 1, 200);
            if (f > s && fPrev <= sPrev) return k;
        }
        return -1;
    }

    /** Simple average of the {@code len} closes ending just before index {@code end}. */
    private static double sma(double[] c, int end, int len) {
        if (end < len) return Double.NaN;
        return Arrays.stream(c, end - len, end).average().orElse(Double.NaN);
    }
}
