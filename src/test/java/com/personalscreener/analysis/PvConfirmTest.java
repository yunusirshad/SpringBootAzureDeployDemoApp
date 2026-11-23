package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PvConfirmTest {

    /** Builds daily bars on weekdays from closes; open = previous close, high/low 0.1 outside the body. */
    static final class Series {
        final List<Bar> bars = new ArrayList<>();
        LocalDate day = LocalDate.of(2025, 1, 6);
        double last = Double.NaN;

        Series add(double close, double volume) { return bar(Double.isNaN(last) ? close : last, close, volume); }

        Series bar(double open, double close, double volume) {
            return bar(open, Math.max(open, close) + 0.1, Math.min(open, close) - 0.1, close, volume);
        }

        Series bar(double open, double high, double low, double close, double volume) {
            long t = day.atTime(9, 30).atZone(MarketClock.ET).toEpochSecond();
            bars.add(new Bar(t, open, high, low, close, volume));
            last = close;
            do { day = day.plusDays(1); } while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY);
            return this;
        }

        Map<LocalDate, Boolean> spy(boolean above) {
            Map<LocalDate, Boolean> m = new HashMap<>();
            for (Bar b : bars) m.put(PvConfirm.date(b), above);
            m.put(day, above);
            for (int i = 1; i < 20; i++) m.put(day.plusDays(i), above);
            return m;
        }
    }

    /** 250 rising bars, then two down days on half volume: RSI(2) about 12 after the first, about 4 after the second (the BUY). */
    static Series pullback() {
        Series s = new Series();
        for (int i = 0; i < 250; i++) s.add(10 + i * 0.05, 1000);
        double c = s.last;
        for (int k = 1; k <= 2; k++) s.add(c - k * 0.35, 500);
        return s;
    }

    @Test
    void allFourRulesInAnUptrendIsBuy() {
        Series s = pullback();
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.BUY, r.status());
        assertEquals(4, r.rules().passed());
        assertTrue(r.rsi() < 10);
        assertTrue(r.volPct() < 100);
        assertTrue(r.entryEstimated());
        assertEquals(r.close(), r.entry(), 1e-9);
        assertEquals(r.close() - 2.5 * r.atr(), r.stop(), 1e-9);
    }

    @Test
    void buyFillsAtTodaysOpenWhileTheBarIsLive() {
        Series s = pullback();
        Bar live = new Bar(s.day.atTime(9, 30).atZone(MarketClock.ET).toEpochSecond(), 22.0, 22.2, 21.9, 22.1, 300);
        var r = PvConfirm.evaluate(s.bars, live, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.IN_TRADE, r.status());
        assertEquals(22.0, r.entry(), 1e-9);
        assertFalse(r.entryEstimated());
    }

    @Test
    void firstProfitableCloseIsTakeProfit() {
        Series s = pullback();
        double c = s.last;
        s.bar(c - 0.05, c + 0.2, 900);              // enters at c - 0.05, closes above it
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.SELL, r.status());
        assertEquals("TAKE PROFIT", r.exitReason());
        assertEquals(c - 0.05, r.entry(), 1e-9);
        assertEquals(c + 0.2, r.exit(), 1e-9);
    }

    @Test
    void lowThroughTheStopIsStop() {
        Series s = pullback();
        double c = s.last;
        s.bar(c, c - 0.1, 900);                     // entry bar, closes under the entry
        var in = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.IN_TRADE, in.status());
        double stop = in.stop();
        s.bar(c - 0.2, c - 0.1, stop - 0.5, c - 0.3, 900);   // trades through the stop
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.SELL, r.status());
        assertEquals("STOP", r.exitReason());
        assertEquals(stop, r.exit(), 1e-9);
    }

    @Test
    void gapBelowTheStopFillsAtTheOpen() {
        Series s = pullback();
        double c = s.last;
        s.bar(c, c - 0.1, 900);
        double stop = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings()).stop();
        s.bar(stop - 1, stop - 0.8, 900);
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals("STOP", r.exitReason());
        assertEquals(stop - 1, r.exit(), 1e-9);
    }

    @Test
    void tenBarsWithoutProfitIsTimeExit() {
        Series s = pullback();
        double c = s.last;
        for (int k = 0; k < 10; k++) s.bar(c - 0.02, c - 0.05, 900);   // never closes above the entry, never near the stop
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.SELL, r.status());
        assertEquals("TIME EXIT", r.exitReason());
        assertEquals(10, r.barsHeld());
    }

    @Test
    void openTradeIsStillTrackedAfterTheTrendFlips() {
        // a barely-rising trend, a BUY, then a collapse that flips SMA50 under SMA200 while the trade is open
        Series s = new Series();
        for (int i = 0; i < 200; i++) s.add(10, 1000);
        for (int i = 0; i < 60; i++) s.add(10 + i * 0.005, 1000);
        double c = s.last;
        for (int k = 1; k <= 2; k++) s.add(c - k * 0.02, 500);
        PvConfirm.Settings set = new PvConfirm.Settings();
        assertEquals(PvConfirm.Status.BUY, PvConfirm.evaluate(s.bars, null, s.spy(true), set).status());
        set.atrMult = 200;                         // a stop far enough away that the collapse does not hit it
        for (int k = 0; k < 4; k++) s.bar(s.last, 6, 900);
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), set);
        assertFalse(r.trendUp(), "SMA50 is now under SMA200");
        assertEquals(PvConfirm.Status.IN_TRADE, r.status());
        assertTrue(r.note().contains("downtrend"));
    }

    @Test
    void downtrendGetsNoEntry() {
        Series s = new Series();
        for (int i = 0; i < 250; i++) s.add(30 - i * 0.05, 1000);
        double c = s.last;
        for (int k = 1; k <= 3; k++) s.add(c - k * 0.3, 500);
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.NA_DOWNTREND, r.status());
        assertTrue(r.trades().isEmpty());
    }

    @Test
    void rsiJustAboveTheBuyLevelIsReadyToBuy() {
        Series s = pullback();
        PvConfirm.Settings set = new PvConfirm.Settings();
        double rsi = PvConfirm.evaluate(s.bars, null, s.spy(true), set).rsi();
        set.rsiBuyBelow = rsi - 0.01;
        set.rsiReadyBelow = rsi + 5;
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), set);
        assertEquals(PvConfirm.Status.READY_TO_BUY, r.status());
        assertEquals(3, r.rules().passed());
        assertTrue(r.nearSetup());
    }

    @Test
    void spyUnderItsSma200BlocksTheBuy() {
        Series s = pullback();
        var r = PvConfirm.evaluate(s.bars, null, s.spy(false), new PvConfirm.Settings());
        assertEquals(PvConfirm.Status.WAIT, r.status());
        assertFalse(r.rules().spyAboveSma());
        assertEquals(3, r.rules().passed());
        assertTrue(r.nearSetup());
        assertTrue(r.note().contains("SPY"));
    }

    @Test
    void settingsAreValidated() {
        PvConfirm.Settings set = new PvConfirm.Settings();
        set.validate();
        set.atrMult = -1;
        assertThrows(IllegalArgumentException.class, set::validate);
    }

    @Test
    void expectedBuyIsTheCloseThatFiresTheSignal() {
        Series s = new Series();
        for (int i = 0; i < 250; i++) s.add(10 + i * 0.05, 1000);
        s.add(s.last - 0.35, 500);                              // one down day: RSI(2) about 12, a WAIT
        PvConfirm.Settings set = new PvConfirm.Settings();
        set.rsiReadyBelow = 10;                                 // keep it a WAIT, not READY
        var r = PvConfirm.evaluate(s.bars, null, s.spy(true), set);
        assertEquals(PvConfirm.Status.WAIT, r.status());
        var ex = r.expected();
        assertNotNull(ex);
        assertTrue(ex.buy() < r.close());
        assertEquals(ex.buy() - 2.5 * r.atr(), ex.stop(), 1e-3);
        assertTrue(ex.target() > ex.buy());
        // closing exactly at the expected buy fires the BUY
        s.add(ex.buy(), 500);
        assertEquals(PvConfirm.Status.BUY, PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings()).status());
    }

    @Test
    void closeJustAboveTheExpectedBuyDoesNotFire() {
        Series s = new Series();
        for (int i = 0; i < 250; i++) s.add(10 + i * 0.05, 1000);
        s.add(s.last - 0.35, 500);
        double buy = PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings()).expected().buy();
        s.add(buy + 0.02, 500);
        assertNotEquals(PvConfirm.Status.BUY, PvConfirm.evaluate(s.bars, null, s.spy(true), new PvConfirm.Settings()).status());
    }
}
