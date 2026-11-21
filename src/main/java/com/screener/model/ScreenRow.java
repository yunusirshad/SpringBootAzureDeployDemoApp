package com.screener.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The full screener row. The screen list, core stocks and the exports all use this same shape. */
public record ScreenRow(
        String symbol,
        String name,
        String exchange,
        Double price,
        Double marketCap,
        Double targetMeanPrice,
        Double upside,
        Double debtToEquity,
        Double debtToAssets,
        Double debtToCapital,
        Double cashToDebt,
        Double revenueGrowth,
        LocalDate earningsDate,
        List<GateResult> gates,
        boolean ratiosPass,
        ZoyaCheck zoya,
        Quality quality,
        GexLevels gex,
        boolean passed,
        List<String> failedReasons,
        Instant updatedAt) {}
