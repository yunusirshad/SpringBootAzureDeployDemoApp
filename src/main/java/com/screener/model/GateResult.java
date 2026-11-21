package com.screener.model;

/** One rule: its value, the threshold as text, and whether the name passed. A missing value fails. */
public record GateResult(String gate, Double value, String threshold, boolean pass) {}
