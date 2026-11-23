package com.personalscreener.analysis;

import com.personalscreener.data.OptionChain;
import com.personalscreener.model.GexLevels;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GEX levels from one or more expiries. Open interest is summed by strike across all of them, so a big monthly
 * position is not hidden by a quiet weekly.
 * <ul>
 *   <li>Call wall: the strike with the most call open interest.</li>
 *   <li>Put wall: the strike with the most put open interest.</li>
 *   <li>King: the strike with the largest absolute net gamma exposure (calls positive, puts negative).</li>
 * </ul>
 * A tie on open interest goes to the strike nearer the spot. Each percent is that strike's share of all open
 * interest in the window.
 */
public final class GexCalculator {

    private GexCalculator() {}

    public static GexLevels compute(OptionChain ch, double spot, LocalDate today) {
        return ch == null ? null : compute(List.of(ch), spot, today);
    }

    public static GexLevels compute(List<OptionChain> chains, double spot, LocalDate today) {
        if (chains == null || chains.isEmpty()) return null;
        double s = spot > 0 ? spot : chains.get(0).spot();
        Map<Double, Double> callByStrike = new TreeMap<>(), putByStrike = new TreeMap<>(), gexByStrike = new TreeMap<>();
        Map<LocalDate, Double> oiByExpiry = new TreeMap<>();
        double callOi = 0, putOi = 0;
        LocalDate last = null;
        for (OptionChain ch : chains) {
            double t = Math.max(1, ChronoUnit.DAYS.between(today, ch.expiry())) / 365.0;
            double expOi = 0;
            for (OptionChain.Strike c : ch.calls()) {
                callOi += c.openInterest();
                expOi += c.openInterest();
                callByStrike.merge(c.strike(), c.openInterest(), Double::sum);
                gexByStrike.merge(c.strike(), gex(c, s, t), Double::sum);
            }
            for (OptionChain.Strike p : ch.puts()) {
                putOi += p.openInterest();
                expOi += p.openInterest();
                putByStrike.merge(p.strike(), p.openInterest(), Double::sum);
                gexByStrike.merge(p.strike(), -gex(p, s, t), Double::sum);
            }
            oiByExpiry.merge(ch.expiry(), expOi, Double::sum);
            if (last == null || ch.expiry().isAfter(last)) last = ch.expiry();
        }
        double total = callOi + putOi;
        if (total <= 0) return null;

        double[] cw = wall(callByStrike, s), pw = wall(putByStrike, s);
        double king = Double.NaN, best = -1;
        for (var e : gexByStrike.entrySet()) {
            double a = Math.abs(e.getValue());
            if (a > best || (a == best && Math.abs(e.getKey() - s) < Math.abs(king - s))) { best = a; king = e.getKey(); }
        }
        double kingOi = callByStrike.getOrDefault(king, 0.0) + putByStrike.getOrDefault(king, 0.0);
        LocalDate mainExpiry = oiByExpiry.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        double netGex = gexByStrike.values().stream().mapToDouble(Double::doubleValue).sum();
        return new GexLevels(mainExpiry,
                cw[0], 100 * cw[1] / total, cw[1],
                king, 100 * kingOi / total,
                pw[0], 100 * pw[1] / total, pw[1],
                total, callOi, putOi, putOi > 0 ? callOi / putOi : Double.NaN, netGex,
                today, chains.size(), last);
    }

    /** One strike of the chain view, summed over the given expiries. */
    public record StrikeRow(double strike, double callOi, double putOi, double callIv, double putIv, double netGex) {}

    /** Open interest, open-interest-weighted IV and net gamma by strike, summed over the given expiries. */
    public static List<StrikeRow> byStrike(List<OptionChain> chains, double spot, LocalDate today) {
        Map<Double, double[]> m = new TreeMap<>();   // callOi, putOi, callIv*oi, putIv*oi, gex, callIvN, putIvN
        for (OptionChain ch : chains) {
            double t = Math.max(1, ChronoUnit.DAYS.between(today, ch.expiry())) / 365.0;
            for (OptionChain.Strike c : ch.calls()) {
                double[] a = m.computeIfAbsent(c.strike(), k -> new double[7]);
                a[0] += c.openInterest();
                if (!Double.isNaN(c.impliedVolatility())) { a[2] += c.impliedVolatility() * Math.max(1, c.openInterest()); a[5] += Math.max(1, c.openInterest()); }
                a[4] += gex(c, spot, t);
            }
            for (OptionChain.Strike p : ch.puts()) {
                double[] a = m.computeIfAbsent(p.strike(), k -> new double[7]);
                a[1] += p.openInterest();
                if (!Double.isNaN(p.impliedVolatility())) { a[3] += p.impliedVolatility() * Math.max(1, p.openInterest()); a[6] += Math.max(1, p.openInterest()); }
                a[4] -= gex(p, spot, t);
            }
        }
        List<StrikeRow> out = new java.util.ArrayList<>();
        for (var e : m.entrySet()) {
            double[] a = e.getValue();
            out.add(new StrikeRow(e.getKey(), a[0], a[1], a[5] > 0 ? a[2] / a[5] : Double.NaN, a[6] > 0 ? a[3] / a[6] : Double.NaN, a[4]));
        }
        return out;
    }

    /** {strike, open interest} of the strike with the most open interest; NaN when none has any. */
    static double[] wall(Map<Double, Double> oiByStrike, double spot) {
        double strike = Double.NaN, oi = 0;
        for (var e : oiByStrike.entrySet()) {
            double v = e.getValue();
            if (v > oi || (v == oi && v > 0 && Math.abs(e.getKey() - spot) < Math.abs(strike - spot))) { oi = v; strike = e.getKey(); }
        }
        return new double[]{strike, oi};
    }

    /** Dollar gamma for a 1% move: gamma x OI x 100 x S^2 x 0.01. */
    private static double gex(OptionChain.Strike k, double s, double t) {
        double iv = Double.isNaN(k.impliedVolatility()) || k.impliedVolatility() <= 0.01 ? 0.5 : k.impliedVolatility();
        if (s <= 0 || k.strike() <= 0) return 0;
        double d1 = (Math.log(s / k.strike()) + 0.5 * iv * iv * t) / (iv * Math.sqrt(t));
        double gamma = Math.exp(-0.5 * d1 * d1) / Math.sqrt(2 * Math.PI) / (s * iv * Math.sqrt(t));
        return gamma * k.openInterest() * 100 * s * s * 0.01;
    }
}
