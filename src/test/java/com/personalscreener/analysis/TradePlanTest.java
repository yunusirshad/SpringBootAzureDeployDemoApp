package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradePlanTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 24);

    private static long et(int h, int m) { return D.atTime(h, m).atZone(MarketClock.ET).toEpochSecond(); }

    private static TradePlan.Setup setup(double entry, double stop, double target) {
        return new TradePlan.Setup("RETEST", "A", 4, List.of(), et(10, 0) - 300, entry, stop, target, (target - entry) / (entry - stop), "x", null, "STRONG_UPTREND");
    }

    private static List<Bar> day(double[][] ohlcFrom1000) {
        List<Bar> l = new ArrayList<>();
        for (int i = 0; i < ohlcFrom1000.length; i++) {
            double[] x = ohlcFrom1000[i];
            l.add(new Bar(et(10, 0) + i * 300L, x[0], x[1], x[2], x[3], 1000));
        }
        return l;
    }

    @Test
    void targetHit() {
        var t = TradePlan.simulate("X", setup(10, 9.8, 10.4), day(new double[][]{{10, 10.1, 9.95, 10.05}, {10.05, 10.45, 10.0, 10.4}}), et(10, 0), 0);
        assertEquals("TARGET", t.exitReason());
        assertEquals(10.4, t.exit(), 1e-9);
        assertEquals(2.0, t.rMultiple(), 1e-9);
        assertTrue(t.win());
    }

    @Test
    void stopIsAssumedWhenACandleTouchesBoth() {
        var t = TradePlan.simulate("X", setup(10, 9.8, 10.4), day(new double[][]{{10, 10.5, 9.7, 10.0}}), et(10, 0), 0);
        assertEquals("STOP", t.exitReason());
        assertEquals(9.8, t.exit(), 1e-9);
    }

    @Test
    void gapThroughTheStopFillsAtTheOpen() {
        var t = TradePlan.simulate("X", setup(10, 9.8, 10.4), day(new double[][]{{10, 10.1, 9.95, 10.0}, {9.6, 9.7, 9.5, 9.6}}), et(10, 0), 0);
        assertEquals(9.6, t.exit(), 1e-9);
    }

    @Test
    void endOfDayExitAt340() {
        int bars = (15 * 60 + 40 - 10 * 60) / 5 + 1;   // 10:00 to 15:40
        double[][] flat = new double[bars][];
        for (int i = 0; i < bars; i++) flat[i] = new double[]{10, 10.1, 9.9, 10.05};
        var t = TradePlan.simulate("X", setup(10, 9.8, 10.4), day(flat), et(10, 0), 0);
        assertEquals("END OF DAY", t.exitReason());
        assertEquals(10.05, t.exit(), 1e-9);
    }

    @Test
    void statsCountWinsByGrade() {
        var w = new TradePlan.Trade("X", "RETEST", "A", 0, 10, 9.8, 10.4, 1, 10.4, "TARGET", 2, 4);
        var l = new TradePlan.Trade("X", "RETEST", "A", 0, 10, 9.8, 10.4, 1, 9.8, "STOP", -1, -2);
        var s = TradePlan.stats(List.of(w, l, w));
        assertEquals(3, s.get("RETEST-A").get("trades"));
        assertEquals(66.7, (double) s.get("ALL-A").get("winRate"), 1e-9);
        assertEquals(4.0, (double) s.get("ALL").get("profitFactor"), 1e-9);   // 4% + 4% won against 2% lost
    }

    // ---- breakeven management ----

    @Test
    void stopMovesToBreakevenAfterPlusOneR() {
        // up 1R (10.2) on the first candle, then back down through the entry
        var t = TradePlan.simulate("X", setup(10, 9.8, 10.4), day(new double[][]{{10, 10.25, 9.95, 10.2}, {10.2, 10.2, 9.9, 9.95}}), et(10, 0), 0);
        assertEquals("BREAKEVEN", t.exitReason());
        assertEquals(10.0, t.exit(), 1e-9);
        var off = TradePlan.simulate("X", setup(10, 9.8, 10.4), day(new double[][]{{10, 10.25, 9.95, 10.2}, {10.2, 10.2, 9.75, 9.8}}), et(10, 0), 0, false);
        assertEquals("STOP", off.exitReason(), "without management the full stop is hit");
    }

    // ---- Fibonacci golden zone ----

    private static List<Bar> hourly(double[] closes) {
        List<Bar> l = new ArrayList<>();
        long t0 = et(9, 30) - closes.length * 3600L;
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i], o = i == 0 ? c : closes[i - 1];
            l.add(new Bar(t0 + i * 3600L, o, Math.max(o, c) + 0.05, Math.min(o, c) - 0.05, c, 1000));
        }
        return l;
    }

    private static List<Bar> fifteen(long from, double[][] ohlc) {
        List<Bar> l = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) l.add(new Bar(from + i * 900L, ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3], 1000));
        return l;
    }

    /** 20 flat hours at 10, a rally to 12, then a pullback: the golden zone is about 10.75-10.99 (0.618-0.5). */
    private static List<Bar> swing() {
        double[] c = new double[50];
        for (int i = 0; i < 20; i++) c[i] = 10;
        for (int i = 20; i < 40; i++) c[i] = 10 + (i - 19) * 0.1;          // up to 12.0
        for (int i = 40; i < 50; i++) c[i] = 12 - (i - 39) * 0.08;          // down to 11.2
        return hourly(c);
    }

    @Test
    void pullbackIntoTheGoldenZoneIsAFibSetup() {
        List<Bar> h1 = swing();
        long after = h1.get(h1.size() - 1).time() + 3600;
        List<Bar> m15 = fifteen(after, new double[][]{{11.2, 11.2, 11.0, 11.05}, {11.05, 11.1, 10.9, 11.0}, {11.0, 11.1, 10.95, 11.05}});
        var f = TradePlan.fib(h1, m15, 0.2);
        assertNotNull(f);
        assertTrue(f.inZone());
        assertEquals(12.05, f.high(), 1e-9);
        assertTrue(f.level(0.786) < 10.5);
    }

    @Test
    void aCloseBelow0786KillsTheSetup() {
        List<Bar> h1 = swing();
        long after = h1.get(h1.size() - 1).time() + 3600;
        double dead = TradePlan.fib(h1, List.of(), 0.2).level(0.786) - 0.05;
        List<Bar> m15 = fifteen(after, new double[][]{{11.0, 11.0, dead - 0.1, dead}, {dead, 11.0, dead, 10.95}});
        assertNull(TradePlan.fib(h1, m15, 0.2));
    }

    @Test
    void aRangeIsNotAFibSetup() {
        double[] c = new double[50];
        for (int i = 0; i < 50; i++) c[i] = 10 + (i % 2 == 0 ? 0.05 : -0.05);
        assertNull(TradePlan.fib(hourly(c), List.of(), 0.2), "a leg under 2 ATR is a range");
    }

    // ---- reversal ----

    @Test
    void sweepAndTurnIsAReversal() {
        double[][] b = new double[28][];
        for (int i = 0; i < 24; i++) b[i] = new double[]{10.2, 10.3, 10.0, 10.2};
        b[24] = new double[]{10.1, 10.15, 9.8, 10.05};   // sweeps the 10.00 lows, closes back above
        b[25] = new double[]{10.05, 10.12, 10.0, 10.1};
        b[26] = new double[]{10.1, 10.2, 10.05, 10.18};
        b[27] = new double[]{10.18, 10.3, 10.15, 10.25}; // closes above the sweep candle's high (10.15)
        Bar prevDay = new Bar(0, 10, 10.5, 9.5, 10.2, 1);
        assertEquals(9.8, TradePlan.reversalLow(fifteen(et(9, 30), b), prevDay), 1e-9);
    }
}
