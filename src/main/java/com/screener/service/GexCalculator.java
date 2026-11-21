package com.screener.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.screener.model.GexLevels;
import com.screener.model.OptionChain;
import com.screener.model.OptionContract;

/**
 * Dealer gamma exposure by strike, assuming dealers are long calls and short puts (the usual convention).
 * Per contract: gamma (Black–Scholes) x open interest x 100 x spot^2 x 1%, calls positive, puts negative.
 * Call wall = strike with the most call gamma; put wall = strike with the most put gamma;
 * king = strike with the largest net gamma of either sign.
 */
@Component
public class GexCalculator {

    private static final double RATE = 0.04;

    public GexLevels compute(OptionChain chain, LocalDate today) {
        double s = chain.spot();
        if (s <= 0) return GexLevels.unavailable(chain.symbol(), "no spot price");
        Map<Double, Double> calls = new TreeMap<>();
        Map<Double, Double> puts = new TreeMap<>();
        TreeSet<String> exps = new TreeSet<>();
        for (OptionContract c : chain.contracts()) {
            if (c.openInterest() <= 0 || c.impliedVolatility() <= 0.01) continue;
            long days = ChronoUnit.DAYS.between(today, c.expiry());
            if (days < 0) continue;
            double t = Math.max(days, 0.5) / 365.0;
            double g = gamma(s, c.strike(), t, c.impliedVolatility());
            double gex = g * c.openInterest() * 100 * s * s * 0.01;
            if (c.call()) calls.merge(c.strike(), gex, Double::sum);
            else puts.merge(c.strike(), -gex, Double::sum);
            exps.add(c.expiry().toString());
        }
        if (calls.isEmpty() && puts.isEmpty()) return GexLevels.unavailable(chain.symbol(), "no open interest");

        Double callWall = argMax(calls, false);
        Double putWall = argMax(puts, true);
        Map<Double, Double> net = new TreeMap<>(calls);
        puts.forEach((k, v) -> net.merge(k, v, Double::sum));
        Double king = argMax(net, true);
        double total = net.values().stream().mapToDouble(Double::doubleValue).sum();
        return new GexLevels(chain.symbol(), s, callWall, king, putWall, total, List.copyOf(exps), Instant.now(), null);
    }

    static double gamma(double s, double k, double t, double vol) {
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (RATE + vol * vol / 2) * t) / (vol * sqrtT);
        return Math.exp(-d1 * d1 / 2) / Math.sqrt(2 * Math.PI) / (s * vol * sqrtT);
    }

    private static Double argMax(Map<Double, Double> m, boolean abs) {
        Double best = null;
        double bestV = -1;
        for (Map.Entry<Double, Double> e : m.entrySet()) {
            double v = abs ? Math.abs(e.getValue()) : e.getValue();
            if (v > bestV) {
                bestV = v;
                best = e.getKey();
            }
        }
        return best;
    }
}
