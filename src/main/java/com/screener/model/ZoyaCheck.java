package com.screener.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A Zoya compliance read. status is COMPLIANT, NON_COMPLIANT, QUESTIONABLE or UNKNOWN.
 * source is LIVE (read just now), CACHE (a good read under 24 hours old), STALE (older, kept
 * only for display because the live read failed) or NONE (never read successfully).
 */
public record ZoyaCheck(String symbol, String status, Instant checkedAt, String source, String error) {

    @JsonIgnore
    public boolean isCompliant() {
        return "COMPLIANT".equals(status);
    }

    @JsonIgnore
    public boolean isFresh() {
        return "LIVE".equals(source) || "CACHE".equals(source);
    }

    public ZoyaCheck withSource(String newSource, String newError) {
        return new ZoyaCheck(symbol, status, checkedAt, newSource, newError);
    }
}
