package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmcZonesTest {

    private static List<Bar> bars(double[][] ohlc) {
        List<Bar> l = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) l.add(new Bar(1_000_000 + i * 900L, ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3], 1000));
        return l;
    }

    @Test
    void findsABullishFvgAndOrderBlock() {
        List<Bar> b = bars(new double[][]{
                {10.0, 10.1, 9.9, 10.0}, {10.0, 10.1, 9.9, 10.0},
                {10.05, 10.1, 9.8, 9.85},   // down candle: the order block
                {9.9, 10.6, 9.9, 10.5},     // displacement up
                {10.5, 11.0, 10.3, 10.9},   // low 10.3 above candle 2's high 10.1: a bullish FVG 10.1-10.3
                {10.9, 11.0, 10.7, 10.8}});
        List<SmcZones.Zone> z = SmcZones.active(b, 0.3, 60, "15m");
        assertTrue(z.stream().anyMatch(x -> x.kind() == SmcZones.Kind.FVG && x.bullish() && x.low() == 10.1 && x.high() == 10.3));
        assertTrue(z.stream().anyMatch(x -> x.kind() == SmcZones.Kind.OB && x.bullish() && x.low() == 9.8 && x.high() == 10.1));
    }

    @Test
    void aCloseThroughTheZoneRemovesIt() {
        List<Bar> b = bars(new double[][]{
                {10.0, 10.1, 9.9, 10.0}, {10.0, 10.1, 9.9, 10.0}, {10.05, 10.1, 9.8, 9.85},
                {9.9, 10.6, 9.9, 10.5}, {10.5, 11.0, 10.3, 10.9},
                {10.9, 10.9, 9.5, 9.6}});   // closes under both zones
        List<SmcZones.Zone> z = SmcZones.active(b, 0.3, 60, "15m");
        assertTrue(z.stream().noneMatch(SmcZones.Zone::bullish));
    }
}
