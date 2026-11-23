package com.personalscreener.service;

import com.personalscreener.analysis.TrendClassifier;
import com.personalscreener.model.StockRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreFilterTest {

    private static StockRow row(String zoya, String trend) {
        StockRow r = new StockRow();
        r.zoya = zoya;
        r.trendClass = trend;
        return r;
    }

    @Test
    void onlyCompliantUptrendsAndBullishReversals() {
        assertTrue(ScreenService.inCore(row("COMPLIANT", TrendClassifier.STRONG)));
        assertTrue(ScreenService.inCore(row("COMPLIANT", TrendClassifier.BULL_REV)));
        assertFalse(ScreenService.inCore(row("COMPLIANT", TrendClassifier.BEAR_REV)));
        assertFalse(ScreenService.inCore(row("COMPLIANT", TrendClassifier.NONE)));
        for (String z : new String[]{"NON_COMPLIANT", "QUESTIONABLE", "NOT_COVERED", "NOT_CHECKED"}) {
            assertFalse(ScreenService.inCore(row(z, TrendClassifier.STRONG)), z);
        }
    }
}
