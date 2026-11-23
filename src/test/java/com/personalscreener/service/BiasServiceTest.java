package com.personalscreener.service;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BiasServiceTest {

    private static long et(LocalDate d, int h, int m) { return d.atTime(h, m).atZone(MarketClock.ET).toEpochSecond(); }

    @Test
    void overnightIsFromThePrior4pmToNow() {
        LocalDate fri = LocalDate.of(2026, 11, 20), mon = LocalDate.of(2026, 11, 23);
        List<Bar> b = new ArrayList<>();
        b.add(new Bar(et(fri, 15, 50), 100, 100, 100, 100.0, 1));
        b.add(new Bar(et(fri, 15, 55), 100, 100, 100, 101.0, 1));   // the last bar before 4:00 PM
        b.add(new Bar(et(fri, 16, 5), 100, 100, 100, 99.0, 1));     // after-hours, ignored for the base
        b.add(new Bar(et(mon, 9, 20), 100, 100, 100, 102.01, 1));
        b.add(new Bar(et(mon, 9, 25), 100, 100, 100, 103.02, 1));   // finishes at 9:30
        b.add(new Bar(et(mon, 9, 30), 100, 100, 100, 110.0, 1));    // after the call: ignored
        double pct = BiasService.overnight(b, fri, et(mon, 9, 30));
        assertEquals((103.02 / 101.0 - 1) * 100, pct, 1e-9);
    }

    @Test
    void messageListsEachFactor() {
        BiasService.Record r = new BiasService.Record();
        r.date = "2026-11-23";
        r.bias = "Bearish";
        r.strength = "moderate";
        r.summary = "Bearish, moderate (-4 of ±10)";
        r.factors = List.of(new com.personalscreener.analysis.MarketBias.Factor("VIX", "28.00", -1, 1));
        assertEquals("Market bias 2026-11-23: BEARISH (moderate)", BiasService.title(r));
        assertTrue(BiasService.body(r).contains("▼ VIX: 28.00 (-1)"));
    }
}
