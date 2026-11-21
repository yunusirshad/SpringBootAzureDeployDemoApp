package com.screener.model;

import java.time.LocalDate;

/** Raw inputs for the gates and the quality score. Any field can be null when the source has no number. */
public record Fundamentals(
        String symbol,
        String name,
        String exchange,
        Double price,
        Double marketCap,
        Double targetMeanPrice,
        Double totalDebt,
        Double totalCash,
        Double totalAssets,
        Double totalEquity,
        Double revenueGrowth,
        LocalDate earningsDate,
        Double grossMargin,
        Double operatingMargin,
        Double profitMargin,
        Double returnOnEquity,
        Double freeCashFlow,
        Double currentRatio) {}
