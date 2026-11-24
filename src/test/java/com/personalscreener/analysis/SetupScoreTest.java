package com.personalscreener.analysis;

import com.personalscreener.model.GexLevels;
import com.personalscreener.model.StockRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SetupScoreTest {

    private static StockRow row(double price, double low, double used) {
        StockRow r = new StockRow();
        r.price = price;
        r.dayLow = low;
        r.atr = 1.0;
        r.rangeUsedPct = used;
        r.longScore = 80;
        r.zoya = "COMPLIANT";
        r.trendClass = TrendClassifier.STRONG;
        return r;
    }

    @Test
    void freshRangeNearSupportBeatsAnExtendedOne() {
        StockRow fresh = row(10.25, 10.0, 20);    // 0.25 risk to the low, target 11.0: 3:1
        StockRow extended = row(10.9, 10.0, 95);  // 0.9 risk, 0.1 reward
        var a = SetupScore.of(fresh);
        var b = SetupScore.of(extended);
        assertEquals(3.0, a.rewardRisk(), 1e-9);
        assertTrue(b.rewardRisk() < 0.2);
        assertTrue(a.score() > b.score() + 30);
    }

    @Test
    void putWallRaisesSupportAndCallWallCapsTheTarget() {
        StockRow r = row(10.5, 10.0, 50);
        r.gex = new GexLevels(LocalDate.of(2026, 12, 18), 10.8, 30, 2000, 10.5, 10, 10.2, 20, 1500,
                5000, 3000, 2000, 1.5, 0, LocalDate.of(2026, 11, 23), 2, LocalDate.of(2026, 12, 18));
        var s = SetupScore.of(r);
        assertEquals(10.2, s.stop(), 1e-9);    // put wall above today's low
        assertEquals(10.8, s.target(), 1e-9);  // call wall below low + ATR (11.0)
        assertEquals(1.0, s.rewardRisk(), 1e-9);
    }

    @Test
    void riskIsAtLeastAQuarterAtr() {
        var s = SetupScore.of(row(10.01, 10.0, 20));   // sitting on the low
        assertEquals(10.01 - 0.25, s.stop(), 1e-9);
        assertEquals(3.96, s.rewardRisk(), 1e-9);
    }

    private static StockRow buyable() {
        StockRow r = row(10.25, 10.0, 30);
        r.rewardRisk = 3.0;
        r.bias = "Bullish";
        r.above1hEma = true;
        r.toBreakoutPct = -0.5;
        r.sessionPhase = "Bull Distribution";
        r.rvolPaced = 2.4;
        r.rvolNeed = 2.0;
        return r;
    }

    @Test
    void lowPacedVolumeBlocksBuyNow() {
        StockRow r = buyable();
        r.rvolPaced = 1.6;
        var s = SetupScore.signal(r);
        assertEquals(SetupScore.READY, s.signal());
        assertTrue(s.notes().get(0).startsWith("RVOL 1.6"));
        r.rvolNeed = 1.5;                       // first hour: 1.5x is enough
        assertEquals(SetupScore.BUY_NOW, SetupScore.signal(r).signal());
    }

    @Test
    void rvolIsPacedToTheTimeOfDay() {
        java.time.ZoneId ny = java.time.ZoneId.of("America/New_York");
        var tue1030 = java.time.ZonedDateTime.of(2026, 11, 24, 10, 30, 0, 0, ny);
        assertEquals(2.0, SetupScore.pacedRelVol(0.5, tue1030));          // 25% of a day by 10:30 -> 0.5 is 2x pace
        assertEquals(2.0, SetupScore.rvolNeed(tue1030));
        assertEquals(1.5, SetupScore.rvolNeed(tue1030.minusMinutes(1)));
        assertEquals(0.5, SetupScore.pacedRelVol(0.5, tue1030.withHour(17))); // after the close: raw
        assertNull(SetupScore.pacedRelVol(null, tue1030));
    }

    @Test
    void allConditionsMetIsBuyNow() {
        assertEquals(SetupScore.BUY_NOW, SetupScore.signal(buyable()).signal());
    }

    @Test
    void setUpButNotTriggeredIsReady() {
        StockRow r = buyable();
        r.toBreakoutPct = 1.8;                 // not through the breakout yet
        var s = SetupScore.signal(r);
        assertEquals(SetupScore.READY, s.signal());
        assertTrue(s.notes().get(0).startsWith("breakout"));
    }

    @Test
    void gatesForceWait() {
        StockRow notCompliant = buyable();
        notCompliant.zoya = "NOT_CHECKED";
        assertEquals(SetupScore.HOLD, SetupScore.signal(notCompliant).signal(), "trend intact, but never a buy");
        StockRow poorRr = buyable();
        poorRr.rewardRisk = 0.6;
        poorRr.toBreakoutPct = 6.0;
        assertEquals(SetupScore.HOLD, SetupScore.signal(poorRr).signal());
        StockRow none = buyable();
        none.trendClass = TrendClassifier.NONE;
        assertEquals(SetupScore.WAIT, SetupScore.signal(none).signal());
    }

    @Test
    void sellSignals() {
        StockRow breaking = buyable();
        breaking.trendClass = TrendClassifier.BEAR_REV;
        breaking.above1hEma = false;
        breaking.bias = "Mixed";
        assertEquals(SetupScore.SELL_NOW, SetupScore.signal(breaking).signal());

        StockRow target = buyable();
        target.gex = new GexLevels(LocalDate.of(2026, 12, 18), 10.2, 30, 2000, 10, 10, 9.5, 20, 1500,
                5000, 3000, 2000, 1.5, 0, LocalDate.of(2026, 11, 23), 2, LocalDate.of(2026, 12, 18));
        assertEquals(SetupScore.SELL_NOW, SetupScore.signal(target).signal(), "price 10.25 is through the 10.20 call wall");

        StockRow weakening = buyable();
        weakening.trendClass = TrendClassifier.BEAR_REV;   // still above the 1h EMA
        assertEquals(SetupScore.READY_SELL, SetupScore.signal(weakening).signal());

        StockRow earnings = buyable();
        earnings.earningsSoon = true;
        assertEquals(SetupScore.READY_SELL, SetupScore.signal(earnings).signal());

        StockRow spent = buyable();
        spent.rangeUsedPct = 105.0;
        assertEquals(SetupScore.READY_SELL, SetupScore.signal(spent).signal());
        spent.sessionPhase = "Bear Distribution";
        spent.sessionPct = 80;
        assertEquals(SetupScore.SELL_NOW, SetupScore.signal(spent).signal());
    }
}
