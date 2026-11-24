package com.personalscreener.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookServiceTest {

    @Test
    void noTradesIsTooFewNotACrash() {
        var m = PlaybookService.summary("X", "t", List.of(), List.of(), List.of());
        assertEquals(0, m.get("trades"));
        assertNull(m.get("profitFactor"));
        assertEquals("too few trades", m.get("verdict"));
    }

    @Test
    void verdicts() {
        List<Double> good = List.of(3.0, 3.0, -1.0, 2.0, -1.0, 3.0, 2.0, -1.0, 1.0);
        assertEquals("edge", PlaybookService.summary("X", "t", good, good, List.of()).get("verdict"));
        List<Double> bad = List.of(1.0, -2.0, 1.0, -2.0, 1.0, -2.0, 1.0, -2.0, 1.0);
        assertEquals("no edge", PlaybookService.summary("X", "t", bad, bad, List.of()).get("verdict"));
        List<Double> few = List.of(5.0, 5.0, 5.0);
        assertEquals("too few trades", PlaybookService.summary("X", "t", few, few, List.of()).get("verdict"));
    }

    @Test
    void sharesComeFromTheRisk() {
        var p = PlaybookService.plan("Breakout", java.util.Map.of(), "buy", 10.0, 9.5, 11.0, "x", 100.0);
        assertEquals(200, p.get("shares"));        // $100 / $0.50 a share
        assertEquals(2.0, p.get("rr"));
        assertEquals(2000.0, p.get("positionValue"));
    }

    @Test
    void positionIsCappedByTheAccount() {
        // $180 risk with a $0.05 stop would be 3,600 shares of a $10 stock = $36,000: capped at $9,000
        var p = PlaybookService.plan("Breakout", java.util.Map.of(), "buy", 10.0, 9.95, 10.1, "x", 180.0, 9000);
        assertEquals(900, p.get("shares"));
        assertEquals(9000.0, p.get("positionValue"));
        assertNotNull(p.get("sizeNote"));
    }

    @Test
    void profileRiskDollars() {
        var pr = new PlaybookService.Profile();
        assertEquals(180.0, pr.riskDollars(), 1e-9);
        assertEquals(2.0, pr.minRR, 1e-9);
    }
}
