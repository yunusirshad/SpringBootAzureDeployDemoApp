package com.personalscreener.model;

/** JSON has no NaN or Infinity: map them to null. */
public final class Num {
    private Num() {}

    public static Double of(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? null : v; }

    public static Double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    public static double d(Double v) { return v == null ? Double.NaN : v; }
}
