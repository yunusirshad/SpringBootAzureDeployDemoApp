package com.personalscreener.analysis;

import com.personalscreener.data.Bar;

import java.util.ArrayList;
import java.util.List;

/** Classic indicators, computed on completed candles. All return NaN when there is not enough history. */
public final class Indicators {

    private Indicators() {}

    public static double[] closes(List<Bar> bars) {
        double[] c = new double[bars.size()];
        for (int i = 0; i < c.length; i++) c[i] = bars.get(i).close();
        return c;
    }

    public static double sma(double[] x, int n) {
        if (x.length < n) return Double.NaN;
        double s = 0;
        for (int i = x.length - n; i < x.length; i++) s += x[i];
        return s / n;
    }

    /** EMA series seeded with the SMA of the first n values; entries before that are NaN. */
    public static double[] emaSeries(double[] x, int n) {
        double[] out = new double[x.length];
        java.util.Arrays.fill(out, Double.NaN);
        if (x.length < n) return out;
        double k = 2.0 / (n + 1), s = 0;
        for (int i = 0; i < n; i++) s += x[i];
        out[n - 1] = s / n;
        for (int i = n; i < x.length; i++) out[i] = x[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    public static double ema(double[] x, int n) {
        double[] e = emaSeries(x, n);
        return e.length == 0 ? Double.NaN : e[e.length - 1];
    }

    /** Wilder RSI. */
    public static double rsi(double[] c, int n) {
        if (c.length <= n) return Double.NaN;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = c[i] - c[i - 1];
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= n; loss /= n;
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
        }
        if (loss == 0) return 100;
        return 100 - 100 / (1 + gain / loss);
    }

    public record Macd(double line, double signal, double histogram) {
        public boolean up() { return line > signal; }
    }

    public static Macd macd(double[] c) {
        if (c.length < 35) return new Macd(Double.NaN, Double.NaN, Double.NaN);
        double[] fast = emaSeries(c, 12), slow = emaSeries(c, 26);
        List<Double> line = new ArrayList<>();
        for (int i = 0; i < c.length; i++) if (!Double.isNaN(slow[i])) line.add(fast[i] - slow[i]);
        double[] l = line.stream().mapToDouble(Double::doubleValue).toArray();
        double[] sig = emaSeries(l, 9);
        double lv = l[l.length - 1], sv = sig[sig.length - 1];
        return new Macd(lv, sv, lv - sv);
    }

    public static double[] trueRanges(List<Bar> b) {
        double[] tr = new double[b.size()];
        for (int i = 0; i < b.size(); i++) {
            Bar x = b.get(i);
            if (i == 0) { tr[i] = x.high() - x.low(); continue; }
            double pc = b.get(i - 1).close();
            tr[i] = Math.max(x.high() - x.low(), Math.max(Math.abs(x.high() - pc), Math.abs(x.low() - pc)));
        }
        return tr;
    }

    /** Wilder ATR. */
    public static double atr(List<Bar> b, int n) {
        if (b.size() <= n) return Double.NaN;
        double[] tr = trueRanges(b);
        double a = 0;
        for (int i = 1; i <= n; i++) a += tr[i];
        a /= n;
        for (int i = n + 1; i < tr.length; i++) a = (a * (n - 1) + tr[i]) / n;
        return a;
    }

    public record Adx(double adx, double plusDi, double minusDi) {
        /** "Rising" in the screen means the positive direction leads. */
        public boolean rising() { return plusDi > minusDi; }
        public boolean strong() { return adx > 25; }
    }

    public static Adx adx(List<Bar> b, int n) {
        if (b.size() < 2 * n + 1) return new Adx(Double.NaN, Double.NaN, Double.NaN);
        double[] tr = trueRanges(b);
        double trS = 0, pS = 0, mS = 0, adx = 0;
        int dxCount = 0;
        double pDi = 0, mDi = 0;
        for (int i = 1; i < b.size(); i++) {
            double up = b.get(i).high() - b.get(i - 1).high();
            double dn = b.get(i - 1).low() - b.get(i).low();
            double pdm = up > dn && up > 0 ? up : 0;
            double mdm = dn > up && dn > 0 ? dn : 0;
            if (i <= n) {
                trS += tr[i]; pS += pdm; mS += mdm;
                if (i < n) continue;
            } else {
                trS = trS - trS / n + tr[i];
                pS = pS - pS / n + pdm;
                mS = mS - mS / n + mdm;
            }
            pDi = trS == 0 ? 0 : 100 * pS / trS;
            mDi = trS == 0 ? 0 : 100 * mS / trS;
            double dx = pDi + mDi == 0 ? 0 : 100 * Math.abs(pDi - mDi) / (pDi + mDi);
            dxCount++;
            if (dxCount <= n) {
                adx += dx;
                if (dxCount == n) adx /= n;
            } else {
                adx = (adx * (n - 1) + dx) / n;
            }
        }
        return new Adx(dxCount >= n ? adx : Double.NaN, pDi, mDi);
    }

    /** Slow stochastic %K: the 14-bar %K smoothed over 3 bars. */
    public static double stochastic(List<Bar> b, int n, int smooth) {
        if (b.size() < n + smooth - 1) return Double.NaN;
        double sum = 0;
        for (int k = 0; k < smooth; k++) {
            int end = b.size() - 1 - k;
            double hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
            for (int i = end - n + 1; i <= end; i++) {
                hh = Math.max(hh, b.get(i).high());
                ll = Math.min(ll, b.get(i).low());
            }
            sum += hh == ll ? 50 : 100 * (b.get(end).close() - ll) / (hh - ll);
        }
        return sum / smooth;
    }

    /** Where the close sits between the 20-bar, 2-sigma Bollinger bands: 0% the lower, 100% the upper. */
    public static double bollingerPosition(double[] c, int n, double k) {
        if (c.length < n) return Double.NaN;
        double mean = sma(c, n), var = 0;
        for (int i = c.length - n; i < c.length; i++) var += (c[i] - mean) * (c[i] - mean);
        double sd = Math.sqrt(var / n);
        double upper = mean + k * sd, lower = mean - k * sd;
        if (upper == lower) return 50;
        return 100 * (c[c.length - 1] - lower) / (upper - lower);
    }

    public static double vwap(List<Bar> b) {
        double pv = 0, v = 0;
        for (Bar x : b) {
            double tp = (x.high() + x.low() + x.close()) / 3;
            pv += tp * x.volume();
            v += x.volume();
        }
        return v == 0 ? Double.NaN : pv / v;
    }

    /** Groups bars into blocks of {@code size} within each New York trading day (e.g. 4 x 1h into 4h). */
    public static List<Bar> aggregateByDay(List<Bar> bars, int size) {
        List<Bar> out = new ArrayList<>();
        List<Bar> block = new ArrayList<>();
        java.time.LocalDate day = null;
        for (Bar x : bars) {
            java.time.LocalDate d = java.time.Instant.ofEpochSecond(x.time()).atZone(com.personalscreener.market.MarketClock.ET).toLocalDate();
            if (day != null && (!d.equals(day) || block.size() == size)) {
                out.add(merge(block));
                block.clear();
            }
            day = d;
            block.add(x);
        }
        if (!block.isEmpty()) out.add(merge(block));
        return out;
    }

    private static Bar merge(List<Bar> b) {
        double h = Double.NEGATIVE_INFINITY, l = Double.POSITIVE_INFINITY, v = 0;
        for (Bar x : b) { h = Math.max(h, x.high()); l = Math.min(l, x.low()); v += x.volume(); }
        return new Bar(b.get(0).time(), b.get(0).open(), h, l, b.get(b.size() - 1).close(), v);
    }
}
