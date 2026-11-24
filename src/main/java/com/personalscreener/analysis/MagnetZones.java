package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The magnet zone: a volume profile over recent candles and the price band where the most volume traded.
 *
 * <p>Each candle's volume is spread evenly across the price bins its high-low range covers (a candle with no volume,
 * such as an index or forex, counts 1, so it becomes a time-at-price profile). The bin with the most volume is the
 * <b>point of control (POC)</b>. Growing out from the POC (always toward the heavier neighbour) until the band holds
 * 20% of all volume gives the <b>magnet zone</b>; until it holds 70% gives the <b>value area</b>.
 *
 * <p>{@link #touchStats} checks whether the zone really pulls price: for each day, from a profile of only the days
 * before it, how often the day's range touched the zone, and on days that opened more than 1 ATR away, how often
 * price reached it, against a "mirror" band of the same width the same distance on the other side of the open.
 */
public final class MagnetZones {

    public static final double CORE_SHARE = 0.20, VALUE_AREA_SHARE = 0.70;

    public record Profile(double poc, double zoneLow, double zoneHigh, double vaLow, double vaHigh, double binSize,
                          int candles, LocalDate from, LocalDate to) {}

    public record Stats(int days, int touched, Double touchPct, int openedAway, int reachedFromAway, Double reachedPct,
                        int mirrorReached, Double mirrorPct, int lookbackDays, String verdict) {}

    private MagnetZones() {}

    public static Profile build(List<Bar> bars) {
        if (bars.isEmpty()) return null;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Bar b : bars) { lo = Math.min(lo, b.low()); hi = Math.max(hi, b.high()); }
        if (!(hi > lo)) return null;
        double ref = bars.get(bars.size() - 1).close();
        double bin = Math.max(ref * 0.001, (hi - lo) / 400);
        int n = (int) Math.ceil((hi - lo) / bin) + 1;
        double[] vol = new double[n];
        for (Bar b : bars) {
            int a = (int) Math.floor((b.low() - lo) / bin), z = (int) Math.floor((b.high() - lo) / bin);
            a = Math.max(0, Math.min(n - 1, a));
            z = Math.max(a, Math.min(n - 1, z));
            double v = b.volume() > 0 ? b.volume() : 1;
            double each = v / (z - a + 1);
            for (int k = a; k <= z; k++) vol[k] += each;
        }
        double total = Arrays.stream(vol).sum();
        int poc = 0;
        for (int k = 1; k < n; k++) if (vol[k] > vol[poc]) poc = k;
        int[] core = grow(vol, poc, total * CORE_SHARE), va = grow(vol, poc, total * VALUE_AREA_SHARE);
        LocalDate from = PvConfirm.date(bars.get(0)), to = PvConfirm.date(bars.get(bars.size() - 1));
        return new Profile(lo + (poc + 0.5) * bin, lo + core[0] * bin, lo + (core[1] + 1) * bin,
                lo + va[0] * bin, lo + (va[1] + 1) * bin, bin, bars.size(), from, to);
    }

    /** Bins [from, to] grown out from {@code start}, toward the heavier neighbour, until they hold {@code target}. */
    static int[] grow(double[] vol, int start, double target) {
        int a = start, z = start;
        double sum = vol[start];
        while (sum < target && (a > 0 || z < vol.length - 1)) {
            double left = a > 0 ? vol[a - 1] : -1, right = z < vol.length - 1 ? vol[z + 1] : -1;
            if (right >= left) sum += vol[++z]; else sum += vol[--a];
        }
        return new int[]{a, z};
    }

    /**
     * Walk-forward test of the zone's pull. For each day after the first {@code lookbackDays}, the profile comes from
     * the {@code lookbackDays} days before it only.
     */
    public static Stats touchStats(List<Bar> bars, int lookbackDays) {
        TreeMap<LocalDate, List<Bar>> byDay = new TreeMap<>();
        for (Bar b : bars) byDay.computeIfAbsent(date(b), d -> new ArrayList<>()).add(b);
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        int tested = 0, touched = 0, away = 0, reached = 0, mirror = 0;
        for (int i = lookbackDays; i < days.size(); i++) {
            List<Bar> hist = new ArrayList<>();
            double rangeSum = 0;
            int rangeN = 0;
            for (int j = i - lookbackDays; j < i; j++) {
                List<Bar> d = byDay.get(days.get(j));
                hist.addAll(d);
                if (j >= i - 14) { rangeSum += high(d) - low(d); rangeN++; }
            }
            Profile p = build(hist);
            if (p == null) continue;
            List<Bar> today = byDay.get(days.get(i));
            double h = high(today), l = low(today), open = today.get(0).open(), atr = rangeN == 0 ? 0 : rangeSum / rangeN;
            tested++;
            boolean hit = h >= p.zoneLow() && l <= p.zoneHigh();
            if (hit) touched++;
            double dist = open > p.zoneHigh() ? open - p.zoneHigh() : open < p.zoneLow() ? p.zoneLow() - open : 0;
            if (atr > 0 && dist > atr) {
                away++;
                if (hit) reached++;
                // the mirror band: same width, same distance, on the other side of the open
                double w = p.zoneHigh() - p.zoneLow();
                double mLow = open > p.zoneHigh() ? open + dist : open - dist - w, mHigh = mLow + w;
                if (h >= mLow && l <= mHigh) mirror++;
            }
        }
        Double tp = tested == 0 ? null : round(100.0 * touched / tested), rp = away == 0 ? null : round(100.0 * reached / away),
                mp = away == 0 ? null : round(100.0 * mirror / away);
        String verdict = verdict(away, reached, rp, mp);
        return new Stats(tested, touched, tp, away, reached, rp, mirror, mp, lookbackDays, verdict);
    }

    static String verdict(int away, int reached, Double rp, Double mp) {
        return away < 8 ? "Too few days opening far from the zone to judge its pull"
                : rp != null && mp != null && rp >= mp + 10 && reached >= 3 ? "The zone pulls price: reached far more often than the mirror band"
                : rp != null && mp != null && rp > mp ? "A slight pull: reached a little more often than the mirror band"
                : "No pull: price reached the zone no more often than a mirror band the same distance away";
    }

    // ---- all timeframes combined ----------------------------------------------------------------------

    /**
     * A band where the heavy zones of two or more timeframes overlap. {@code score} is the most that agree anywhere in
     * it, and {@code coreLow}-{@code coreHigh} is where they do.
     */
    public record Zone(double low, double high, double coreLow, double coreHigh, int score, List<String> members) {
        public boolean contains(double p) { return p >= low && p <= high; }
    }

    /** The volume profiles the confluence combines, each built only from data before {@code day}. */
    public static Map<String, Profile> profiles(List<Bar> m5, List<Bar> daily, LocalDate day) {
        TreeMap<LocalDate, List<Bar>> byDay = new TreeMap<>();
        for (Bar b : m5) { LocalDate d = date(b); if (d.isBefore(day)) byDay.computeIfAbsent(d, x -> new ArrayList<>()).add(b); }
        List<LocalDate> ds = new ArrayList<>(byDay.keySet());
        Map<String, Profile> out = new LinkedHashMap<>();
        if (!ds.isEmpty()) put(out, "yesterday", byDay.get(ds.get(ds.size() - 1)));
        put(out, "5 days", lastDays(byDay, ds, 5));
        put(out, "20 days", lastDays(byDay, ds, 20));
        List<Bar> d1 = daily.stream().filter(b -> date(b).isBefore(day)).toList();
        put(out, "3 months", d1.subList(Math.max(0, d1.size() - 63), d1.size()));
        put(out, "1 year", d1.subList(Math.max(0, d1.size() - 252), d1.size()));
        return out;
    }

    private static List<Bar> lastDays(TreeMap<LocalDate, List<Bar>> byDay, List<LocalDate> ds, int n) {
        List<Bar> l = new ArrayList<>();
        for (int i = Math.max(0, ds.size() - n); i < ds.size(); i++) l.addAll(byDay.get(ds.get(i)));
        return l;
    }

    private static void put(Map<String, Profile> m, String k, List<Bar> bars) {
        if (bars.size() < 5) return;
        Profile p = build(bars);
        if (p != null) m.put(k, p);
    }

    /**
     * Bands where two or more timeframes' magnet zones overlap, strongest first (ties: the narrower band). Each
     * timeframe's zone is its 20% core around the POC.
     */
    public static List<Zone> confluence(Map<String, Profile> profiles) {
        TreeSet<Double> edges = new TreeSet<>();
        for (Profile p : profiles.values()) { edges.add(p.zoneLow()); edges.add(p.zoneHigh()); }
        List<Double> e = new ArrayList<>(edges);
        List<Zone> out = new ArrayList<>();
        Zone cur = null;
        for (int i = 0; i + 1 < e.size(); i++) {
            double a = e.get(i), b = e.get(i + 1), mid = (a + b) / 2;
            List<String> who = new ArrayList<>();
            for (var en : profiles.entrySet()) if (mid >= en.getValue().zoneLow() && mid <= en.getValue().zoneHigh()) who.add(en.getKey());
            if (who.size() >= 2) {
                if (cur != null && Math.abs(cur.high() - a) < 1e-12) {
                    LinkedHashSet<String> m = new LinkedHashSet<>(cur.members());
                    m.addAll(who);
                    boolean stronger = who.size() > cur.score(), same = who.size() == cur.score() && Math.abs(cur.coreHigh() - a) < 1e-12;
                    double cl = stronger ? a : cur.coreLow(), ch = stronger || same ? b : cur.coreHigh();
                    cur = new Zone(cur.low(), b, cl, ch, Math.max(cur.score(), who.size()), new ArrayList<>(m));
                } else {
                    if (cur != null) out.add(cur);
                    cur = new Zone(a, b, a, b, who.size(), who);
                }
            } else if (cur != null) { out.add(cur); cur = null; }
        }
        if (cur != null) out.add(cur);
        out.sort(Comparator.comparingInt((Zone z) -> -z.score()).thenComparingDouble(z -> z.high() - z.low()));
        return out;
    }

    /**
     * The same walk-forward pull test for the combined zones: each day, the strongest confluence zone (most
     * timeframes; ties go to the one nearest the open) from data before that day.
     */
    public static Stats confluenceStats(List<Bar> m5, List<Bar> daily, int minDays) {
        TreeMap<LocalDate, List<Bar>> byDay = new TreeMap<>();
        for (Bar b : m5) byDay.computeIfAbsent(date(b), d -> new ArrayList<>()).add(b);
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        int tested = 0, touched = 0, away = 0, reached = 0, mirror = 0;
        for (int i = minDays; i < days.size(); i++) {
            LocalDate day = days.get(i);
            List<Bar> today = byDay.get(day);
            double open = today.get(0).open(), h = high(today), l = low(today);
            List<Zone> zs = confluence(profiles(m5, daily, day));
            if (zs.isEmpty()) continue;
            int top = zs.get(0).score();
            Zone z = zs.stream().filter(x -> x.score() == top)
                    .min(Comparator.comparingDouble(x -> Math.min(Math.abs(open - x.low()), Math.abs(open - x.high())))).orElse(zs.get(0));
            double rangeSum = 0;
            int rn = 0;
            for (int j = Math.max(0, i - 14); j < i; j++) { List<Bar> d = byDay.get(days.get(j)); rangeSum += high(d) - low(d); rn++; }
            double atr = rn == 0 ? 0 : rangeSum / rn;
            tested++;
            boolean hit = h >= z.low() && l <= z.high();
            if (hit) touched++;
            double dist = open > z.high() ? open - z.high() : open < z.low() ? z.low() - open : 0;
            if (atr > 0 && dist > atr) {
                away++;
                if (hit) reached++;
                double w = z.high() - z.low(), mLow = open > z.high() ? open + dist : open - dist - w;
                if (h >= mLow && l <= mLow + w) mirror++;
            }
        }
        Double tp = tested == 0 ? null : round(100.0 * touched / tested), rp = away == 0 ? null : round(100.0 * reached / away),
                mp = away == 0 ? null : round(100.0 * mirror / away);
        return new Stats(tested, touched, tp, away, reached, rp, mirror, mp, minDays, verdict(away, reached, rp, mp));
    }

    private static double high(List<Bar> d) { return d.stream().mapToDouble(Bar::high).max().orElse(Double.NaN); }

    private static double low(List<Bar> d) { return d.stream().mapToDouble(Bar::low).min().orElse(Double.NaN); }

    private static LocalDate date(Bar b) { return Instant.ofEpochSecond(b.time()).atZone(MarketClock.ET).toLocalDate(); }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
