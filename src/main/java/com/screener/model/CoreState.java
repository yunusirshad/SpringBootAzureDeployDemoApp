package com.screener.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Core stocks for one New York day, keyed by symbol. */
public record CoreState(LocalDate nyDate, Map<String, ScreenRow> rows) {
    public static CoreState forDay(LocalDate day) {
        return new CoreState(day, new LinkedHashMap<>());
    }
}
