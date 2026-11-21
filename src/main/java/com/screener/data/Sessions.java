package com.screener.data;

import java.time.Instant;
import java.time.LocalTime;

import com.screener.config.AppConfig;

/** Tags an intraday bar PRE, REGULAR or POST by its New York start time. */
public final class Sessions {
    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(16, 0);

    private Sessions() {}

    public static String of(Instant start) {
        LocalTime t = start.atZone(AppConfig.NEW_YORK).toLocalTime();
        if (t.isBefore(OPEN)) return "PRE";
        if (t.isBefore(CLOSE)) return "REGULAR";
        return "POST";
    }
}
