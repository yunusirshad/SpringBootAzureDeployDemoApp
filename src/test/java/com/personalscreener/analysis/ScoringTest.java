package com.personalscreener.analysis;

import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.data.OptionChain;
import com.personalscreener.model.GexLevels;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoringTest {

    private final QualityScorer scorer = new QualityScorer(new ScreenerProperties());

    @Test
    void perfectNameScoresHundred() {
        QualityScorer.Result r = scorer.score(1.2, 5_000_000, 20, 20_000_000, 6);
        assertEquals(100, r.score());
        assertTrue(r.pass());
    }

    @Test
    void missingFloatFails() {
        QualityScorer.Result r = scorer.score(1.2, 5_000_000, 20, Double.NaN, 6);
        assertFalse(r.pass());
        assertTrue(r.reasons().contains("Float unknown"));
    }

    @Test
    void noQuoteIsHalfMarksNotAFail() {
        QualityScorer.Result r = scorer.score(1.2, 5_000_000, Double.NaN, 20_000_000, 6);
        assertEquals(90, r.score());
        assertTrue(r.pass());
    }

    @Test
    void thinDollarVolumeFails() {
        QualityScorer.Result r = scorer.score(1.2, 1_000_000, 20, 20_000_000, 6);
        assertFalse(r.pass());
    }

    @Test
    void clipFades() {
        assertEquals(20, QualityScorer.clip(1, 0.5, 2), 1e-9);
        assertEquals(10, QualityScorer.clip(Math.sqrt(10) * 2, 0.5, 2), 1e-9);
        assertEquals(0, QualityScorer.clip(25, 0.5, 2), 1e-9);
    }

    @Test
    void states() {
        assertEquals("Bear initiation", StateClassifier.classify(-6.6, 7.7, 1.39, 0.1));
        assertEquals("Bear distribution", StateClassifier.classify(-8.5, 8.1, 0.77, 0.4));
        assertEquals("Bear manipulation", StateClassifier.classify(-1.1, 6.1, 1.28, 0.5));
        assertEquals("Bull accumulation", StateClassifier.classify(0.5, 5.0, 1.11, 0.5));
        assertEquals("Bull manipulation", StateClassifier.classify(0.5, 5.0, 0.6, 0.5));
        assertEquals("Bull initiation", StateClassifier.classify(6, 5.0, 2, 0.9));
    }

    @Test
    void gexWallsAndKing() {
        OptionChain ch = new OptionChain("X", LocalDate.of(2026, 11, 27), 10, List.of(
                new OptionChain.Strike(10, 100, 0.6), new OptionChain.Strike(12, 900, 0.6)), List.of(
                new OptionChain.Strike(8, 600, 0.6), new OptionChain.Strike(10, 400, 0.6)));
        GexLevels g = GexCalculator.compute(ch, 10, LocalDate.of(2026, 11, 22));
        assertNotNull(g);
        assertEquals(12, g.callWall());
        assertEquals(8, g.putWall());
        assertEquals(2000, g.totalOi());
        assertEquals(45, g.callWallPct(), 1e-9);
        assertEquals(1.0, g.callPutRatio(), 1e-9);
        assertFalse(Double.isNaN(g.king()));
    }
}
