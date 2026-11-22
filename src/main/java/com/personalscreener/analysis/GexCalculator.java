package com.personalscreener.analysis;

import com.personalscreener.data.OptionChain;
import com.personalscreener.model.GexLevels;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * Call wall: the call strike with the most open interest. Put wall: the put strike with the most.
 * King: the strike with the largest absolute net gamma exposure (calls positive, puts negative).
 * Each percent is that strike's share of all open interest on the expiry.
 */
public final class GexCalculator {

    private GexCalculator() {}

    public static GexLevels compute(OptionChain ch, double spot, LocalDate today) {
        if (ch == null || (ch.calls().isEmpty() && ch.puts().isEmpty())) return null;
        double s = spot > 0 ? spot : ch.spot();
        double t = Math.max(1, ChronoUnit.DAYS.between(today, ch.expiry())) / 365.0;
        double callOi = 0, putOi = 0;
        Map<Double, Double> oiByStrike = new TreeMap<>(), gexByStrike = new TreeMap<>();
        OptionChain.Strike callWall = null, putWall = null;
        for (OptionChain.Strike c : ch.calls()) {
            callOi += c.openInterest();
            oiByStrike.merge(c.strike(), c.openInterest(), Double::sum);
            gexByStrike.merge(c.strike(), gex(c, s, t), Double::sum);
            if (callWall == null || c.openInterest() > callWall.openInterest()) callWall = c;
        }
        for (OptionChain.Strike p : ch.puts()) {
            putOi += p.openInterest();
            oiByStrike.merge(p.strike(), p.openInterest(), Double::sum);
            gexByStrike.merge(p.strike(), -gex(p, s, t), Double::sum);
            if (putWall == null || p.openInterest() > putWall.openInterest()) putWall = p;
        }
        double total = callOi + putOi;
        if (total <= 0) return null;
        double king = Double.NaN, best = -1;
        for (var e : gexByStrike.entrySet()) {
            if (Math.abs(e.getValue()) > best) { best = Math.abs(e.getValue()); king = e.getKey(); }
        }
        double netGex = gexByStrike.values().stream().mapToDouble(Double::doubleValue).sum();
        return new GexLevels(ch.expiry(),
                callWall == null ? Double.NaN : callWall.strike(), callWall == null ? Double.NaN : 100 * callWall.openInterest() / total,
                king, 100 * oiByStrike.getOrDefault(king, 0.0) / total,
                putWall == null ? Double.NaN : putWall.strike(), putWall == null ? Double.NaN : 100 * putWall.openInterest() / total,
                total, callOi, putOi, putOi > 0 ? callOi / putOi : Double.NaN, netGex);
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
