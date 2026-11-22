package com.personalscreener.analysis;

import com.personalscreener.data.Bar;

import java.util.Arrays;
import java.util.List;

/**
 * Quantum Shift: catches the jump from a coiled, quiet state to a new directional regime.
 *
 * <p>Four parts, scored into a 0-100 "energy" reading:
 * <ul>
 *   <li><b>Coil (30)</b>: Bollinger bandwidth near the bottom of its last 100 bars at any point in the last 5 bars.</li>
 *   <li><b>Break (30)</b>: how far the close sits outside the 20 EMA, in ATRs (2 ATR or more is full marks).</li>
 *   <li><b>Momentum (25)</b>: the 10-bar rate of change as a z-score against its last 50 bars (|z| of 2 is full marks).</li>
 *   <li><b>Volume (15)</b>: bar volume against its 20-bar average (2x is full marks).</li>
 * </ul>
 *
 * <p>A <b>shift up</b> fires when the close is above the 20 EMA + 1 ATR, momentum z is positive, energy is at
 * least the threshold, and the regime was not already up. A <b>shift down</b> is the mirror. The regime goes
 * back to neutral when the close crosses back through the 20 EMA.
 */
public final class QuantumShift {

    public static final int LEN = 20, ATR_LEN = 14, ROC_LEN = 10, Z_LEN = 50, BW_LOOKBACK = 100, COIL_WINDOW = 5;
    public static final double BAND_MULT = 1.0;

    private QuantumShift() {}

    /** Per-bar readings. Arrays line up with the input bars; NaN during warm-up. */
    public record Series(double[] ema, double[] atr, double[] upper, double[] lower, double[] energy,
                         double[] momentumZ, double[] coil, int[] regime, int[] signal) {
        public int length() { return ema.length; }
    }

    public static Series compute(List<Bar> bars, double threshold) {
        int n = bars.size();
        double[] c = Indicators.closes(bars);
        double[] ema = Indicators.emaSeries(c, LEN);
        double[] atr = atrSeries(bars, ATR_LEN);
        double[] upper = nan(n), lower = nan(n), energy = nan(n), z = nan(n), coil = nan(n);
        int[] regime = new int[n], signal = new int[n];

        double[] bw = nan(n), roc = nan(n), relVol = nan(n);
        for (int i = 0; i < n; i++) {
            if (i >= LEN - 1) {
                double mean = 0, var = 0;
                for (int k = i - LEN + 1; k <= i; k++) mean += c[k];
                mean /= LEN;
                for (int k = i - LEN + 1; k <= i; k++) var += (c[k] - mean) * (c[k] - mean);
                bw[i] = mean == 0 ? Double.NaN : 4 * Math.sqrt(var / LEN) / mean;
                double v = 0;
                for (int k = i - LEN + 1; k <= i; k++) v += bars.get(k).volume();
                v /= LEN;
                relVol[i] = v > 0 ? bars.get(i).volume() / v : Double.NaN;
            }
            if (i >= ROC_LEN && c[i - ROC_LEN] > 0) roc[i] = c[i] / c[i - ROC_LEN] - 1;
        }

        // bandwidth percentile rank against its own recent history
        double[] bwRank = nan(n);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(bw[i]) || i < LEN - 1 + 20) continue;
            int from = Math.max(LEN - 1, i - BW_LOOKBACK + 1), below = 0, count = 0;
            for (int k = from; k <= i; k++) {
                if (Double.isNaN(bw[k])) continue;
                count++;
                if (bw[k] <= bw[i]) below++;
            }
            bwRank[i] = count == 0 ? Double.NaN : (double) below / count;
        }

        int state = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(ema[i]) || Double.isNaN(atr[i])) { regime[i] = state; continue; }
            upper[i] = ema[i] + BAND_MULT * atr[i];
            lower[i] = ema[i] - BAND_MULT * atr[i];

            if (i >= ROC_LEN + Z_LEN) {
                double m = 0, s = 0;
                int cnt = 0;
                for (int k = i - Z_LEN + 1; k <= i; k++) if (!Double.isNaN(roc[k])) { m += roc[k]; cnt++; }
                m /= Math.max(1, cnt);
                for (int k = i - Z_LEN + 1; k <= i; k++) if (!Double.isNaN(roc[k])) s += (roc[k] - m) * (roc[k] - m);
                double sd = Math.sqrt(s / Math.max(1, cnt));
                z[i] = sd == 0 ? 0 : (roc[i] - m) / sd;
            }
            double minRank = Double.NaN;
            for (int k = Math.max(0, i - COIL_WINDOW + 1); k <= i; k++) {
                if (!Double.isNaN(bwRank[k])) minRank = Double.isNaN(minRank) ? bwRank[k] : Math.min(minRank, bwRank[k]);
            }
            coil[i] = Double.isNaN(minRank) ? Double.NaN : 1 - minRank;

            if (!Double.isNaN(z[i]) && !Double.isNaN(coil[i])) {
                double brk = atr[i] > 0 ? Math.abs(c[i] - ema[i]) / atr[i] : 0;
                double rv = Double.isNaN(relVol[i]) ? 1 : relVol[i];
                energy[i] = 30 * clamp(coil[i]) + 30 * clamp(brk / 2) + 25 * clamp(Math.abs(z[i]) / 2) + 15 * clamp(rv - 1);
            }

            // regime machine
            if (state == 1 && c[i] < ema[i]) state = 0;
            if (state == -1 && c[i] > ema[i]) state = 0;
            if (!Double.isNaN(energy[i]) && energy[i] >= threshold) {
                if (state != 1 && c[i] > upper[i] && z[i] > 0) { state = 1; signal[i] = 1; }
                else if (state != -1 && c[i] < lower[i] && z[i] < 0) { state = -1; signal[i] = -1; }
            }
            regime[i] = state;
        }
        return new Series(ema, atr, upper, lower, energy, z, coil, regime, signal);
    }

    static double[] atrSeries(List<Bar> b, int n) {
        double[] out = nan(b.size());
        if (b.size() <= n) return out;
        double[] tr = Indicators.trueRanges(b);
        double a = 0;
        for (int i = 1; i <= n; i++) a += tr[i];
        a /= n;
        out[n] = a;
        for (int i = n + 1; i < tr.length; i++) { a = (a * (n - 1) + tr[i]) / n; out[i] = a; }
        return out;
    }

    private static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }

    private static double[] nan(int n) { double[] d = new double[n]; Arrays.fill(d, Double.NaN); return d; }
}
