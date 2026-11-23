package com.personalscreener.data;

/** A point-in-time quote. Any field may be NaN when the source did not supply it. */
public record Quote(String symbol, double price, double previousClose, double dayHigh, double dayLow,
                   double volume, double bid, double ask, double preMarketHigh, long marketTime) {

    public boolean hasBidAsk() { return bid > 0 && ask > 0 && ask >= bid; }

    public double spreadBps() {
        if (!hasBidAsk()) return Double.NaN;
        double mid = (bid + ask) / 2;
        return (ask - bid) / mid * 10_000;
    }

    public double changePct() {
        return previousClose > 0 ? (price / previousClose - 1) * 100 : Double.NaN;
    }
}
