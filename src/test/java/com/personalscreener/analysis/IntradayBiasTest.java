package com.personalscreener.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntradayBiasTest {

    @Test
    void strongTapeIsBullish() {
        var r = IntradayBias.score(new IntradayBias.Inputs(
                705, 700, 695, 702, 600, 598, 220, 219, 700, 705, 703, 701, 3000, 1500, 18, 20, null));
        // above OR high +2, three above VWAP +3, +0.71% from open +1, 15m up +1, breadth 2.0 +1, VIX -10% +1
        assertEquals(9, r.score());
        assertEquals("Bullish", r.bias());
        assertEquals("strong", r.strength());
    }

    @Test
    void weakTapeIsBearish() {
        var r = IntradayBias.score(new IntradayBias.Inputs(
                690, 700, 695, 698, 590, 598, 210, 219, 700, 690, 695, 697, 1000, 3000, 22, 20, null));
        assertEquals(-9, r.score());
        assertEquals("Bearish", r.bias());
    }

    @Test
    void insideTheOpeningRangeWithMixedReadsIsNeutral() {
        double n = Double.NaN;
        var r = IntradayBias.score(new IntradayBias.Inputs(
                698, 700, 695, 697, 600, 601, n, n, 698, n, n, n, null, null, n, n, null));
        assertEquals(0, r.score());   // SPY above VWAP +1, QQQ below -1, the rest n/a or flat
        assertEquals("Neutral", r.bias());
    }
}
