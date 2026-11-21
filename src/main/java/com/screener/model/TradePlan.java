package com.screener.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Long or short plan from the daily levels. side LONG, SHORT or NONE; bias BULLISH, BEARISH or NEUTRAL;
 * state WAITING (entry not hit), TRIGGERED (through the entry), INVALID (not triggered and already through the stop), NO_TRADE.
 */
public record TradePlan(
        String symbol,
        LocalDate levelsDate,
        String side,
        String bias,
        String state,
        double price,
        double priorHigh,
        double priorLow,
        double priorClose,
        double pivot,
        double r1,
        double s1,
        double atr14,
        double sma20,
        Double entry,
        Double stop,
        Double target1,
        Double target2,
        Double riskPerShare,
        int shares,
        double accountSize,
        double riskPercent,
        double riskDollars,
        double positionValue,
        List<String> notes) {}
