package com.screener.model;

import java.time.LocalDate;

public record OptionContract(boolean call, double strike, LocalDate expiry, long openInterest, double impliedVolatility) {}
