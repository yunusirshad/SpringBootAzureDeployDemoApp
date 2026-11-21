package com.screener.model;

import java.time.LocalDate;

/** One name in the daily bot snapshot. */
public record SnapshotEntry(
        String symbol,
        boolean core,
        boolean mine,
        String side,
        String bias,
        String state,
        String zoya,
        Integer qualityScore,
        String qualityGrade,
        Double price,
        Double entry,
        Double stop,
        Double target1,
        Integer shares,
        LocalDate earningsDate,
        String error) {}
