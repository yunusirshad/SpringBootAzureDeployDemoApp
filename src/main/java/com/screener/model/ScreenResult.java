package com.screener.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ScreenResult(
        Instant runAt,
        LocalDate nyDate,
        String provider,
        int universeCount,
        int bandCount,
        int evaluatedCount,
        int ratioPassCount,
        int zoyaCompliantCount,
        List<ScreenRow> rows,
        List<ScreenRow> rejected,
        List<String> warnings) {

    public static ScreenResult empty() {
        return new ScreenResult(null, null, null, 0, 0, 0, 0, 0, List.of(), List.of(), List.of("No screen has run yet."));
    }
}
