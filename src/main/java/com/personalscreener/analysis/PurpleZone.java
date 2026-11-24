package com.personalscreener.analysis;

import java.util.Comparator;
import java.util.List;

/**
 * Where the price sits between its strong purple zones (multi-timeframe magnet zones, {@link MagnetZones}), long only.
 *
 * <ul>
 *   <li><b>Buy zone</b>: the strongest zone (most timeframes agreeing; ties: nearest) whose band top is below the price:
 *       support. <b>Sell zone</b>: the strongest zone whose band bottom is above the price: resistance / take-profit.
 *       A zone the price is inside is the <b>current zone</b>; the buy and sell zones are then the ones below and above.</li>
 *   <li>Distances are in ATR (14-day) and %. {@code pos} = buyDist / (buyDist + sellDist): 0 at the buy zone, 1 at the
 *       sell zone.</li>
 *   <li><b>Status</b>: AT_BUY (inside the buy zone or within 0.25 ATR above it), NEAR_BUY (within 1 ATR, or pos at most
 *       0.33), AT_SELL (within 0.25 ATR below the sell zone), NEAR_SELL (within 1 ATR, or pos 0.67 or more), MIDDLE,
 *       INSIDE (in a zone that is neither), BROKE_SUPPORT (the previous close was at or above the zone now above the
 *       price, and the price has fallen below it), NONE (no strong zone either side).</li>
 *   <li><b>Plan</b> (AT_BUY / NEAR_BUY): entry at the buy zone's top, stop just under its bottom (0.1 ATR), target at the
 *       sell zone's bottom; shown only when R:R meets the minimum. Shares = risk dollars / (entry - stop), capped at the
 *       account.</li>
 * </ul>
 */
public final class PurpleZone {

    public static final int DEFAULT_MIN_SCORE = 3;

    public enum Status { AT_BUY, NEAR_BUY, MIDDLE, NEAR_SELL, AT_SELL, INSIDE, BROKE_SUPPORT, NONE }

    public record Plan(double entry, double stop, double target, double rr, Integer shares, Double positionValue, Double dollarsAtRisk) {}

    public record Reading(Status status, String closerTo, MagnetZones.Zone buyZone, MagnetZones.Zone sellZone,
                          MagnetZones.Zone currentZone, Double buyDistAtr, Double buyDistPct, Double sellDistAtr,
                          Double sellDistPct, Double pos, Plan plan, String note) {
        /** Sort order: at the buy zone first, broken / none last. */
        public int rank() {
            return switch (status) {
                case AT_BUY -> 0; case NEAR_BUY -> 1; case MIDDLE -> 2; case INSIDE -> 3;
                case NEAR_SELL -> 4; case AT_SELL -> 5; case BROKE_SUPPORT -> 6; case NONE -> 7;
            };
        }
    }

    private PurpleZone() {}

    public static Reading classify(double price, double atr, double prevClose, List<MagnetZones.Zone> zones, int minScore,
                                   double account, double riskDollars, double minRR) {
        if (!(price > 0) || zones == null) return new Reading(Status.NONE, null, null, null, null, null, null, null, null, null, null, "No zones");
        List<MagnetZones.Zone> strong = zones.stream().filter(z -> z.score() >= minScore).toList();
        MagnetZones.Zone current = strong.stream().filter(z -> price >= z.low() && price <= z.high())
                .max(Comparator.comparingInt(MagnetZones.Zone::score)).orElse(null);
        double floor = current == null ? price : current.low(), ceil = current == null ? price : current.high();
        Comparator<MagnetZones.Zone> strongestThenNearest = Comparator.comparingInt((MagnetZones.Zone z) -> -z.score());
        MagnetZones.Zone buy = strong.stream().filter(z -> z.high() < floor)
                .min(strongestThenNearest.thenComparingDouble(z -> floor - z.high())).orElse(null);
        MagnetZones.Zone sell = strong.stream().filter(z -> z.low() > ceil)
                .min(strongestThenNearest.thenComparingDouble(z -> z.low() - ceil)).orElse(null);
        boolean haveAtr = atr > 0;
        Double bAtr = buy == null || !haveAtr ? null : round((price - buy.high()) / atr);
        Double bPct = buy == null ? null : round((price / buy.high() - 1) * 100);
        Double sAtr = sell == null || !haveAtr ? null : round((sell.low() - price) / atr);
        Double sPct = sell == null ? null : round((sell.low() / price - 1) * 100);
        Double pos = buy != null && sell != null ? round((price - buy.high()) / (sell.low() - buy.high())) : null;

        Status st;
        if (sell != null && !Double.isNaN(prevClose) && prevClose >= sell.low() && price < sell.low()) st = Status.BROKE_SUPPORT;
        else if (current != null) st = Status.INSIDE;
        else if (buy == null && sell == null) st = Status.NONE;
        else if (bAtr != null && bAtr <= 0.25) st = Status.AT_BUY;
        else if (sAtr != null && sAtr <= 0.25) st = Status.AT_SELL;
        else if ((bAtr != null && bAtr <= 1) || (pos != null && pos <= 0.33)) st = Status.NEAR_BUY;
        else if ((sAtr != null && sAtr <= 1) || (pos != null && pos >= 0.67)) st = Status.NEAR_SELL;
        else st = Status.MIDDLE;
        // a price inside a zone that is also the nearest support counts as at the buy zone
        if (st == Status.INSIDE && current != null && price - current.low() <= (current.high() - current.low()) / 2) {
            buy = current;
            bAtr = 0.0;
            bPct = 0.0;
            st = Status.AT_BUY;
        }
        String closer = buy == null && sell == null ? null : buy == null ? "SELL" : sell == null ? "BUY"
                : (price - buy.high()) <= (sell.low() - price) ? "BUY" : "SELL";

        Plan plan = null;
        String note = null;
        if ((st == Status.AT_BUY || st == Status.NEAR_BUY) && buy != null) {
            double entry = buy.high(), stop = buy.low() - 0.1 * Math.max(atr, 0);
            if (sell == null) note = "No strong zone above for a target";
            else {
                double rr = (sell.low() - entry) / (entry - stop);
                if (entry > stop && rr >= minRR - 1e-9) {
                    int shares = (int) Math.floor(riskDollars / (entry - stop));
                    if (account > 0) shares = Math.min(shares, (int) Math.floor(account / entry));
                    plan = new Plan(round(entry), round(stop), round(sell.low()), round(rr), shares, round(shares * entry), round(shares * (entry - stop)));
                } else note = String.format("R:R %.1f under the 1:%.0f minimum: skip", rr, minRR);
            }
        } else if (st == Status.AT_SELL || st == Status.NEAR_SELL) note = "Near resistance: take profit or tighten the stop; no new long";
        else if (st == Status.BROKE_SUPPORT) note = "Closed through the zone below: it failed as support; no long";
        else if (st == Status.INSIDE) note = "Inside a zone: wait for a close above it (support) or below it (resistance)";
        else if (st == Status.MIDDLE) note = "Between the zones: no edge from them";
        return new Reading(st, closer, buy, sell, current, bAtr, bPct, sAtr, sPct, pos, plan, note);
    }

    private static Double round(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? null : Math.round(v * 100) / 100.0; }
}
