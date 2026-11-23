package com.personalscreener.data;

import java.time.LocalDate;

/** A name from the screen, with the fundamentals the filters use. NaN means TradingView had no value. */
public record Candidate(
        String symbol, String exchange, String name, double close, double changePct, double volume,
        double avgVolume10, double avgVolume30, double relVolume, double marketCap, double targetPrice,
        double debtToEquity, double totalDebt, double totalAssets, double totalEquity, double cash,
        double revenueGrowth, double floatShares, LocalDate earningsDate, String source) {

    public double targetUpsidePct() { return targetPrice > 0 && close > 0 ? (targetPrice / close - 1) * 100 : Double.NaN; }

    public double debtToAssets() { return ratio(totalDebt, totalAssets); }

    public double debtToCapital() {
        return Double.isNaN(totalDebt) || Double.isNaN(totalEquity) ? Double.NaN : ratio(totalDebt, totalDebt + totalEquity);
    }

    /** Cash over debt; a company with no debt is treated as infinitely covered. */
    public double cashToDebt() {
        if (Double.isNaN(cash) || Double.isNaN(totalDebt)) return Double.NaN;
        return totalDebt <= 0 ? Double.POSITIVE_INFINITY : cash / totalDebt;
    }

    private static double ratio(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b) || b <= 0) return Double.NaN;
        return a / b;
    }

    public Candidate withSource(String s) {
        return new Candidate(symbol, exchange, name, close, changePct, volume, avgVolume10, avgVolume30, relVolume,
                marketCap, targetPrice, debtToEquity, totalDebt, totalAssets, totalEquity, cash, revenueGrowth,
                floatShares, earningsDate, s);
    }
}
