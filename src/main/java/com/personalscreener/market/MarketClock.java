package com.personalscreener.market;

import org.springframework.stereotype.Component;

import java.time.*;
import java.util.Set;

/** New York market hours, the 2026-2027 NYSE holidays and the screener's refresh window. */
@Component
public class MarketClock {

    public static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime PRE_OPEN = LocalTime.of(4, 0);
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(16, 0);
    private static final LocalTime EARLY_CLOSE = LocalTime.of(13, 0);
    private static final LocalTime POST_CLOSE = LocalTime.of(20, 0);

    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16),
            LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19),
            LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 11, 26),
            LocalDate.of(2026, 12, 25),
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 18), LocalDate.of(2027, 2, 15),
            LocalDate.of(2027, 3, 26), LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 18),
            LocalDate.of(2027, 7, 5), LocalDate.of(2027, 9, 6), LocalDate.of(2027, 11, 25),
            LocalDate.of(2027, 12, 24));

    private static final Set<LocalDate> EARLY_CLOSES = Set.of(
            LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 24),
            LocalDate.of(2027, 11, 26));

    private final Clock clock;

    public MarketClock() { this(Clock.systemUTC()); }

    public MarketClock(Clock clock) { this.clock = clock; }

    public ZonedDateTime now() { return ZonedDateTime.now(clock).withZoneSameInstant(ET); }

    public boolean isTradingDay(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !HOLIDAYS.contains(d);
    }

    public LocalTime closeTime(LocalDate d) { return EARLY_CLOSES.contains(d) ? EARLY_CLOSE : CLOSE; }

    public boolean isRegularSession() { return isRegularSession(now()); }

    public boolean isRegularSession(ZonedDateTime t) {
        LocalDate d = t.toLocalDate();
        LocalTime lt = t.toLocalTime();
        return isTradingDay(d) && !lt.isBefore(OPEN) && lt.isBefore(closeTime(d));
    }

    /** Pre-market, regular or after-hours on a trading day (4:00 AM to 8:00 PM ET). */
    public boolean isExtendedSession(ZonedDateTime t) {
        LocalTime lt = t.toLocalTime();
        return isTradingDay(t.toLocalDate()) && !lt.isBefore(PRE_OPEN) && lt.isBefore(POST_CLOSE);
    }

    /** The screen refreshes from Monday 7:00 AM to Friday 8:00 PM ET. */
    public boolean inRefreshWindow(ZonedDateTime t) {
        DayOfWeek w = t.getDayOfWeek();
        LocalTime lt = t.toLocalTime();
        return switch (w) {
            case SATURDAY, SUNDAY -> false;
            case MONDAY -> !lt.isBefore(LocalTime.of(7, 0));
            case FRIDAY -> lt.isBefore(POST_CLOSE);
            default -> true;
        };
    }

    public boolean inRefreshWindow() { return inRefreshWindow(now()); }

    public String sessionLabel() {
        ZonedDateTime t = now();
        if (!isTradingDay(t.toLocalDate())) return "Closed";
        LocalTime lt = t.toLocalTime();
        if (lt.isBefore(PRE_OPEN)) return "Closed";
        if (lt.isBefore(OPEN)) return "Pre-market";
        if (lt.isBefore(closeTime(t.toLocalDate()))) return "Open";
        if (lt.isBefore(POST_CLOSE)) return "After hours";
        return "Closed";
    }

    /** Trading days strictly between today and the date (0 when it is the next session). */
    public int tradingDaysUntil(LocalDate from, LocalDate to) {
        if (to == null || to.isBefore(from)) return -1;
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isTradingDay(d)) n++;
        }
        return n;
    }
}
