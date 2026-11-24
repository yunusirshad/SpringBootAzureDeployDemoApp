package com.personalscreener.analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PurpleZoneTest {

    private static MagnetZones.Zone z(double lo, double hi, int score) {
        return new MagnetZones.Zone(lo, hi, lo, hi, score, List.of());
    }

    private static PurpleZone.Reading read(double price, double prev, List<MagnetZones.Zone> zones) {
        return PurpleZone.classify(price, 0.87, prev, zones, 3, 9000, 180, 2);
    }

    @Test
    void nearBuyZoneWithPlan() {
        PurpleZone.Reading r = read(17.10, 17.0, List.of(z(16.76, 17.03, 4), z(18.40, 18.62, 3)));
        assertEquals(PurpleZone.Status.AT_BUY, r.status()); // 0.07 / 0.87 = 0.08 ATR above the zone top
        assertEquals("BUY", r.closerTo());
        assertNotNull(r.plan());
        assertEquals(17.03, r.plan().entry());
        assertEquals(18.40, r.plan().target());
        assertTrue(r.plan().rr() >= 2);
        assertTrue(r.plan().shares() * r.plan().entry() <= 9000);
    }

    @Test
    void nearBuyWithinOneAtr() {
        PurpleZone.Reading r = read(17.60, 17.5, List.of(z(16.76, 17.03, 4), z(19.40, 19.62, 3)));
        assertEquals(PurpleZone.Status.NEAR_BUY, r.status());
    }

    @Test
    void weakZonesIgnoredAndNearSell() {
        PurpleZone.Reading r = read(18.20, 18.1, List.of(z(16.76, 17.03, 3), z(17.90, 18.00, 1), z(18.40, 18.62, 3)));
        assertEquals(PurpleZone.Status.AT_SELL, r.status());
        assertEquals("SELL", r.closerTo());
        assertNull(r.plan());
    }

    @Test
    void brokeSupport() {
        PurpleZone.Reading r = read(16.60, 16.90, List.of(z(16.76, 17.03, 4), z(15.00, 15.20, 3)));
        assertEquals(PurpleZone.Status.BROKE_SUPPORT, r.status());
    }

    @Test
    void lowerHalfOfZoneIsAtBuy_noZonesIsNone() {
        assertEquals(PurpleZone.Status.AT_BUY, read(16.80, 16.8, List.of(z(16.76, 17.03, 4), z(18.4, 18.6, 3))).status());
        assertEquals(PurpleZone.Status.INSIDE, read(17.00, 17.0, List.of(z(16.76, 17.03, 4), z(18.4, 18.6, 3))).status());
        assertEquals(PurpleZone.Status.NONE, read(17.00, 17.0, List.of()).status());
    }

    @Test
    void ranksOrderBuyFirst() {
        assertTrue(read(17.10, 17.0, List.of(z(16.76, 17.03, 4), z(18.40, 18.62, 3))).rank()
                < read(18.30, 18.3, List.of(z(16.76, 17.03, 4), z(18.40, 18.62, 3))).rank());
    }
}
