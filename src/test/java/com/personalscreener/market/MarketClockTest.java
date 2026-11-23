package com.personalscreener.market;

import com.personalscreener.service.WatchlistService;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketClockTest {

    private final MarketClock clock = new MarketClock();

    private static ZonedDateTime et(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, MarketClock.ET);
    }

    @Test
    void refreshWindow() {
        assertFalse(clock.inRefreshWindow(et(2026, 11, 22, 12, 0)));   // Sunday
        assertFalse(clock.inRefreshWindow(et(2026, 11, 23, 6, 59)));   // Monday before 7
        assertTrue(clock.inRefreshWindow(et(2026, 11, 23, 7, 0)));
        assertTrue(clock.inRefreshWindow(et(2026, 11, 23, 7, 15)));
        assertTrue(clock.inRefreshWindow(et(2026, 11, 23, 16, 0)));    // the 4:00 PM run
        assertFalse(clock.inRefreshWindow(et(2026, 11, 23, 16, 15)));
        assertFalse(clock.inRefreshWindow(et(2026, 11, 25, 2, 0)));    // overnight
        assertFalse(clock.inRefreshWindow(et(2026, 11, 26, 10, 0)));   // Thanksgiving
        assertTrue(clock.inRefreshWindow(et(2026, 11, 27, 15, 45)));   // Friday
    }

    @Test
    void holidaysAndEarlyClose() {
        assertFalse(clock.isTradingDay(LocalDate.of(2026, 11, 26)));  // Thanksgiving
        assertEquals(LocalTime.of(13, 0), clock.closeTime(LocalDate.of(2026, 11, 27)));
        assertTrue(clock.isRegularSession(et(2026, 11, 23, 9, 30)));
        assertFalse(clock.isRegularSession(et(2026, 11, 27, 13, 30)));
    }

    @Test
    void tradingDaysToEarnings() {
        LocalDate sunday = LocalDate.of(2026, 11, 22);
        assertEquals(1, clock.tradingDaysUntil(sunday, LocalDate.of(2026, 11, 23)));
        // Mon, Tue, Wed, (Thanksgiving), Fri
        assertEquals(4, clock.tradingDaysUntil(sunday, LocalDate.of(2026, 11, 27)));
        assertEquals(-1, clock.tradingDaysUntil(sunday, LocalDate.of(2026, 11, 20)));
    }

    @Test
    void tickerParsing() {
        assertEquals(List.of("SRTA", "AUR", "EVLV", "AAPL", "BRK.B"),
                WatchlistService.parse("SRTA, aur;EVLV\nNASDAQ:AAPL $BRK.B aur"));
        assertTrue(WatchlistService.parse("123 !!").isEmpty());
    }
}
