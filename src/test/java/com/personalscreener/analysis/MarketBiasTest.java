package com.personalscreener.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketBiasTest {

    private static MarketBias.Inputs in(double es, double nq, double gap, double close, double ema, double s50, double s200,
                                        double m5, double vix, double vixAvg) {
        return new MarketBias.Inputs(es, nq, gap, close, ema, s50, s200, m5, vix, vixAvg, null, null);
    }

    @Test
    void everythingUpIsStrongBullish() {
        var r = MarketBias.score(in(0.6, 0.8, 0.5, 700, 690, 680, 650, 2.0, 14, 15));
        assertEquals("Bullish", r.bias());
        assertEquals("strong", r.strength());
        assertEquals(8, r.score());          // 2+1+1+1+1+1+1, sessions n/a
        assertEquals(10, r.max());
    }

    @Test
    void everythingDownIsStrongBearish() {
        var r = MarketBias.score(in(-0.7, -0.9, -0.6, 690, 720, 735, 733, -3.0, 28, 20));
        assertEquals("Bearish", r.bias());
        assertEquals("strong", r.strength());
        assertEquals(-8, r.score());
    }

    @Test
    void mixedSignalsAreNeutralWithALean() {
        var r = MarketBias.score(in(0.15, 0.1, 0.0, 700, 705, 680, 650, 0.2, 18, 18));
        // ES +1, SPY under its EMA -1, regime +1 -> +1
        assertEquals("Neutral", r.bias());
        assertEquals("bullish", r.lean());
        assertTrue(r.summary().contains("leaning bullish"));
    }

    @Test
    void missingDataScoresZero() {
        double n = Double.NaN;
        var r = MarketBias.score(in(n, n, n, n, n, n, n, n, n, n));
        assertEquals(0, r.score());
        assertEquals("Neutral", r.bias());
    }
}
