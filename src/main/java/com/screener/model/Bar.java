package com.screener.model;

import java.time.Instant;

/** One bar. session is PRE, REGULAR or POST (New York time) for intraday bars, DAY for daily bars. */
public record Bar(Instant time, double open, double high, double low, double close, long volume, String session) {}
