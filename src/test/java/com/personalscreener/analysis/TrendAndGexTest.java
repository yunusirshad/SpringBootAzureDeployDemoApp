package com.personalscreener.analysis;

import com.personalscreener.data.OptionChain;
import com.personalscreener.model.GexLevels;
import com.personalscreener.model.StockRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrendAndGexTest {

    private static OptionChain.Strike k(double strike, double oi) { return new OptionChain.Strike(strike, oi, 0.6); }

    @Test
    void wallsUseTheHighestOpenInterestAcrossExpiries() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        // weekly: 11 calls look biggest on their own; the monthly holds far more at 13
        OptionChain weekly = new OptionChain("X", LocalDate.of(2026, 11, 27), 10,
                List.of(k(11, 500), k(13, 100)), List.of(k(9, 300), k(8, 50)));
        OptionChain monthly = new OptionChain("X", LocalDate.of(2026, 12, 18), 10,
                List.of(k(11, 200), k(13, 2_000)), List.of(k(9, 100), k(8, 1_500)));
        GexLevels g = GexCalculator.compute(List.of(weekly, monthly), 10, today);
        assertEquals(13, g.callWall());
        assertEquals(2_100, g.callWallOi());
        assertEquals(8, g.putWall());
        assertEquals(1_550, g.putWallOi());
        assertEquals(LocalDate.of(2026, 12, 18), g.expiry(), "expiry is the one holding the most open interest");
        assertEquals(2, g.expiries());
        assertEquals(today, g.asOf());
        assertEquals(4_750, g.totalOi());
    }

    @Test
    void wallTieGoesToTheStrikeNearerSpot() {
        OptionChain ch = new OptionChain("X", LocalDate.of(2026, 11, 27), 10,
                List.of(k(15, 400), k(11, 400)), List.of(k(5, 300), k(9, 300)));
        GexLevels g = GexCalculator.compute(ch, 10, LocalDate.of(2026, 11, 23));
        assertEquals(11, g.callWall());
        assertEquals(9, g.putWall());
    }

    @Test
    void noOpenInterestGivesNoLevels() {
        OptionChain ch = new OptionChain("X", LocalDate.of(2026, 11, 27), 10, List.of(k(11, 0)), List.of(k(9, 0)));
        assertNull(GexCalculator.compute(ch, 10, LocalDate.of(2026, 11, 23)));
    }

    private static double[] series(int n, java.util.function.IntToDoubleFunction f) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = f.applyAsDouble(i);
        return c;
    }

    @Test
    void steadyRiseIsStrongUptrend() {
        var r = TrendClassifier.classify(series(250, i -> 10 + i * 0.05));
        assertEquals(TrendClassifier.STRONG, r.trendClass());
    }

    @Test
    void uptrendThatLosesThe50DayIsEarlyBearishReversal() {
        // rise for 230 days, then drop hard for 20
        var r = TrendClassifier.classify(series(250, i -> i < 230 ? 10 + i * 0.05 : 21.5 - (i - 230) * 0.4));
        assertEquals(TrendClassifier.BEAR_REV, r.trendClass());
    }

    @Test
    void downtrendThatReclaimsIsEarlyBullishReversal() {
        // fall for 220 days, then a sharp rally that reclaims the 50-day while it is still under the 200-day
        var r = TrendClassifier.classify(series(250, i -> i < 220 ? 30 - i * 0.08 : 12.4 + (i - 220) * 0.35));
        assertEquals(TrendClassifier.BULL_REV, r.trendClass());
    }

    @Test
    void steadyFallIsNoUptrend() {
        assertEquals(TrendClassifier.NONE, TrendClassifier.classify(series(250, i -> 30 - i * 0.05)).trendClass());
    }

    @Test
    void missingStructureStaysNoUptrend() {
        var r = TrendClassifier.classify(series(150, i -> 10 + i * 0.05));
        assertEquals(TrendClassifier.NONE, r.trendClass());
    }

    @Test
    void longScoreRanksAStrongSetupAboveAWeakOne() {
        StockRow strong = new StockRow();
        strong.trendClass = TrendClassifier.STRONG;
        strong.bias = "Bullish";
        strong.above1hEma = true;
        strong.side = "Long";
        strong.macdUp = true;
        strong.rsi = 60.0;
        strong.qualityScore = 90;
        strong.relVol = 1.4;
        strong.zoya = "COMPLIANT";
        strong.price = 10.0;
        strong.atr = 0.5;
        strong.gex = new GexLevels(LocalDate.of(2026, 12, 18), 12, 30, 2000, 10, 10, 8, 20, 1500,
                5000, 3000, 2000, 1.5, 0, LocalDate.of(2026, 11, 23), 2, LocalDate.of(2026, 12, 18));
        assertEquals(99, LongScore.of(strong).score()); // quality 90 is worth 9

        StockRow weak = new StockRow();
        weak.trendClass = TrendClassifier.BEAR_REV;
        weak.bias = "Bearish";
        weak.side = "Short";
        weak.rsi = 35.0;
        weak.qualityScore = 40;
        weak.earningsSoon = true;
        assertTrue(LongScore.of(weak).score() < 10);
    }
}
