package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MagnetZonesTest {

    private static Bar bar(long t, double lo, double hi, double vol) { return new Bar(t, lo, hi, lo, hi, vol); }

    @Test
    void pocSitsWhereTheVolumeIs() {
        List<Bar> b = new ArrayList<>();
        for (int i = 0; i < 50; i++) b.add(bar(i * 300L, 10.0, 10.2, 10_000));   // heavy trade at 10.0-10.2
        for (int i = 0; i < 50; i++) b.add(bar((50 + i) * 300L, 10.8, 11.0, 500)); // light trade higher up
        var p = MagnetZones.build(b);
        assertTrue(p.poc() >= 10.0 && p.poc() <= 10.2, "POC " + p.poc());
        assertTrue(p.zoneLow() >= 9.99 && p.zoneHigh() <= 10.25);
        assertTrue(p.vaLow() <= p.zoneLow() && p.vaHigh() >= p.zoneHigh());
    }

    @Test
    void overlappingTimeframesMakeAConfluenceZone() {
        Map<String, MagnetZones.Profile> m = new LinkedHashMap<>();
        m.put("5 days", new MagnetZones.Profile(10.1, 10.0, 10.3, 9.8, 10.5, 0.01, 100, null, null));
        m.put("20 days", new MagnetZones.Profile(10.2, 10.1, 10.4, 9.7, 10.6, 0.01, 100, null, null));
        m.put("1 year", new MagnetZones.Profile(10.25, 10.2, 10.35, 9.0, 11.0, 0.01, 100, null, null));
        m.put("3 months", new MagnetZones.Profile(12.0, 11.9, 12.1, 11.0, 13.0, 0.01, 100, null, null));   // alone: no zone
        var z = MagnetZones.confluence(m);
        assertFalse(z.isEmpty());
        assertEquals(3, z.get(0).score());
        assertEquals(10.1, z.get(0).low(), 1e-9);       // the band: where at least two agree
        assertEquals(10.35, z.get(0).high(), 1e-9);     // above 10.35 only the 20-day zone is left
        assertEquals(10.2, z.get(0).coreLow(), 1e-9);   // the core: where all three agree
        assertEquals(10.3, z.get(0).coreHigh(), 1e-9);
        assertTrue(z.stream().noneMatch(x -> x.contains(12.0)), "a single timeframe is not a confluence zone");
    }

    @Test
    void oneLuckyHitIsNotAPull() {
        assertTrue(MagnetZones.verdict(8, 1, 12.5, 0.0).startsWith("A slight pull"));
        assertTrue(MagnetZones.verdict(20, 6, 30.0, 5.0).startsWith("The zone pulls price"));
        assertTrue(MagnetZones.verdict(5, 3, 60.0, 0.0).startsWith("Too few"));
    }

    @Test
    void dailyProfilesCoverThreeTimeframes() {
        List<Bar> d = new ArrayList<>();
        for (int i = 0; i < 300; i++) d.add(bar(i * 86_400L, 10 + (i % 7) * 0.1, 10.3 + (i % 7) * 0.1, 1000));
        var p = MagnetZones.dailyProfiles(d);
        assertEquals(List.of("20 days", "3 months", "1 year"), new ArrayList<>(p.keySet()));
    }

    @Test
    void breakoutWithoutMagnetIsUnchanged() {
        List<Bar> d = new ArrayList<>();
        double c = 10;
        for (int i = 0; i < 400; i++) {
            double o = c;
            c = c * (1 + ((i * 37 % 11) - 4.5) / 300.0) + 0.01;   // a choppy uptrend
            d.add(new Bar(1_600_000_000L + i * 86_400L, o, Math.max(o, c) + 0.1, Math.min(o, c) - 0.1, c, 1000 + i));
        }
        assertEquals(DailyBreakout.backtest(d, 2.0, 5, 5), DailyBreakout.backtest(d, 2.0, 5, 5, false));
    }
}
