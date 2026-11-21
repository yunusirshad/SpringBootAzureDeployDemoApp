package com.screener.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record Snapshot(LocalDate nyDate, Instant generatedAt, List<SnapshotEntry> entries) {}
