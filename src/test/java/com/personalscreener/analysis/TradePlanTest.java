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
}
