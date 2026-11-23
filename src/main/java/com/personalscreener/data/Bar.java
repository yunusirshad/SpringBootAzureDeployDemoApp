package com.personalscreener.data;

/** One OHLCV candle. {@code time} is epoch seconds (the bar's open). */
public record Bar(long time, double open, double high, double low, double close, double volume) {}
