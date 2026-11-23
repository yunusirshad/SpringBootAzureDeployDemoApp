package com.personalscreener.data;

import java.time.LocalDate;
import java.util.List;

/** Open interest by strike for one expiry. */
public record OptionChain(String symbol, LocalDate expiry, double spot, List<Strike> calls, List<Strike> puts) {
    public record Strike(double strike, double openInterest, double impliedVolatility) {}
}
