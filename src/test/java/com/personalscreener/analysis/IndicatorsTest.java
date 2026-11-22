package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorsTest {

    private static List<Bar> line(int n, double start, double step) {
        List<Bar> b = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = start + i * step;
            b.add(new Bar(1_700_000_000L + i * 86_400L, c, c + 0.5, c - 0.5, c, 1000));
        }
        return b;
    }

    @Test
    void smaAndEma() {
        double[] x = {1, 2, 3, 4, 5};
        assertEquals(4.0, Indicators.sma(x, 3), 1e-9);
        assertTrue(Double.isNaN(Indicators.sma(x, 6)));
        double[] flat = {5, 5, 5, 5, 5, 5};
        assertEquals(5.0, Indicators.ema(flat, 3), 1e-9);
    }

    @Test
    void rsiExtremes() {
        double[] up = Indicators.closes(line(40, 10, 1));
        assertEquals(100, Indicators.rsi(up, 14), 1e-9);
        double[] down = Indicators.closes(line(40, 100, -1));
        assertEquals(0, Indicators.rsi(down, 14), 1e-9);
    }

    @Test
    void macdUpInUptrend() {
        double[] c = new double[80];
        for (int i = 0; i < c.length; i++) c[i] = 10 + i * i * 0.01; // accelerating
        Indicators.Macd m = Indicators.macd(c);
        assertTrue(m.line() > 0);
        assertTrue(m.up());
    }

    @Test
    void adxShowsDirection() {
        Indicators.Adx up = Indicators.adx(line(60, 10, 1), 14);
        assertTrue(up.rising());
        assertTrue(up.adx() > 25);
        Indicators.Adx down = Indicators.adx(line(60, 100, -1), 14);
        assertFalse(down.rising());
    }

    @Test
    void atrOfConstantRangeBars() {
        assertEquals(1.0, Indicators.atr(line(30, 10, 0), 14), 1e-9);
    }

    @Test
    void stochasticAndBand() {
        List<Bar> up = line(30, 10, 1);
        assertTrue(Indicators.stochastic(up, 14, 3) > 90);
        assertTrue(Indicators.bollingerPosition(Indicators.closes(up), 20, 2) > 80);
    }

    @Test
    void vwapWeightsByVolume() {
        List<Bar> b = List.of(new Bar(0, 10, 10, 10, 10, 100), new Bar(1, 20, 20, 20, 20, 300));
        assertEquals(17.5, Indicators.vwap(b), 1e-9);
    }

    @Test
    void aggregatesFourHoursPerDay() {
        List<Bar> h = new ArrayList<>();
        long day1 = 1_763_649_000L; // 2025-11-20 09:30 ET
        for (int i = 0; i < 7; i++) h.add(new Bar(day1 + i * 3600L, 1, 2 + i, 0.5, 1.5, 10));
        long day2 = day1 + 86_400L;
        for (int i = 0; i < 3; i++) h.add(new Bar(day2 + i * 3600L, 1, 2, 0.5, 1.5, 10));
        List<Bar> four = Indicators.aggregateByDay(h, 4);
        assertEquals(3, four.size());
        assertEquals(40, four.get(0).volume(), 1e-9);
        assertEquals(8, four.get(1).high(), 1e-9);
    }
}
