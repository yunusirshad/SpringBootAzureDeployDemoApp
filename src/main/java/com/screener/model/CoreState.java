package com.screener.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core stocks. rows = the screen's compliant names for one New York day. mine = names you added yourself:
 * they stay until you remove them, across days, and may be non-compliant.
 */
public record CoreState(LocalDate nyDate, Map<String, ScreenRow> rows, Map<String, ScreenRow> mine) {

    public CoreState {
        rows = rows == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rows);
        mine = mine == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mine);
    }

    public static CoreState forDay(LocalDate day) {
        return new CoreState(day, null, null);
    }

    public CoreState nextDay(LocalDate day) {
        return new CoreState(day, null, mine);
    }
}
