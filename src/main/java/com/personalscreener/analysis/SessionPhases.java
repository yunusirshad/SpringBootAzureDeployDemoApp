package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;

import java.time.*;
import java.util.*;

/**
 * Reads every Asian, London and New York session and scores four phases, each 0-100%:
 *
 * <ul>
 *   <li><b>Accumulation</b>: a tight, balanced session. 100% when the range is half its usual size or less and
 *       the body is small; 0% at 1.25x the usual range. It must be quiet: the score fades once effort passes 1x and
 *       is gone at 2.5x (heavy effort in a tight range is absorption). Bull when it closes in the upper half.</li>
 *   <li><b>Manipulation</b>: a run on the previous session's high or low that closes back inside it (a stop hunt).
 *       100% when price gives back 60% or more of the session range from the swept extreme. A sweep of the high
 *       is Bear; a sweep of the low is Bull.</li>
 *   <li><b>Absorption</b>: big effort, little result. Effort is volume against the usual for that session (or, with
 *       no volume, the path the 5-minute candles travelled). 100% at 2x effort with no net move. Bull when it closes
 *       in the upper half (buyers soaking up selling), Bear in the lower.</li>
 *   <li><b>Distribution</b>: delivery, the expansion leg. 100% when the body is 70% or more of a range at least its
 *       usual size. Bull when it closes above the open, Bear below.</li>
 * </ul>
 *
 * Everything comes from 5-minute candles, so the result is the same whatever timeframe the chart shows.
 * Sessions in New York time: Asia 18:00-02:00, London 02:00-08:00, New York 08:00-17:00.
 */
public final class SessionPhases {

    public enum Name { ASIA("Asia"), LONDON("London"), NEW_YORK("New York");
        public final String label;
        Name(String l) { label = l; }
    }

    public static final LocalTime ASIA_START = LocalTime.of(18, 0), LONDON_START = LocalTime.of(2, 0),
            NY_START = LocalTime.of(8, 0), NY_END = LocalTime.of(17, 0);
    private static final int REF_SESSIONS = 10;

    public record Phase(String name, int pct, String dir) {}

    public record Session(String session, LocalDate tradeDate, long start, long end, boolean live, int bars,
                          double open, double high, double low, double close, double volume,
                          double rangeVsUsual, double effort, String sweep,
                          Phase accumulation, Phase manipulation, Phase absorption, Phase distribution,
                          String label, String dir, int pct) {}

    private SessionPhases() {}

    /** Which session a bar opening at this instant belongs to, and that session's trade date; null between 17:00 and 18:00. */
    static Map.Entry<Name, LocalDate> slot(long epoch) {
        ZonedDateTime t = Instant.ofEpochSecond(epoch).atZone(MarketClock.ET);
        LocalTime lt = t.toLocalTime();
        LocalDate d = t.toLocalDate();
        if (!lt.isBefore(ASIA_START)) return Map.entry(Name.ASIA, d.plusDays(1));
        if (lt.isBefore(LONDON_START)) return Map.entry(Name.ASIA, d);
        if (lt.isBefore(NY_START)) return Map.entry(Name.LONDON, d);
        if (lt.isBefore(NY_END)) return Map.entry(Name.NEW_YORK, d);
        return null;
    }

    static long[] window(Name n, LocalDate tradeDate) {
        ZonedDateTime s, e;
        switch (n) {
            case ASIA -> { s = tradeDate.minusDays(1).atTime(ASIA_START).atZone(MarketClock.ET); e = tradeDate.atTime(LONDON_START).atZone(MarketClock.ET); }
            case LONDON -> { s = tradeDate.atTime(LONDON_START).atZone(MarketClock.ET); e = tradeDate.atTime(NY_START).atZone(MarketClock.ET); }
            default -> { s = tradeDate.atTime(NY_START).atZone(MarketClock.ET); e = tradeDate.atTime(NY_END).atZone(MarketClock.ET); }
        }
        return new long[]{s.toEpochSecond(), e.toEpochSecond()};
    }

    /** @param base 5-minute candles, oldest first, with extended hours. */
    public static List<Session> read(List<Bar> base, long nowEpoch) { return read(base, nowEpoch, false); }

    /** @param noAsia true for stocks and ETFs: their evening after-hours prints are not an Asian session. */
    public static List<Session> read(List<Bar> base, long nowEpoch, boolean noAsia) {
        // 1. group candles into sessions, keeping order
        LinkedHashMap<String, List<Bar>> groups = new LinkedHashMap<>();
        Map<String, Map.Entry<Name, LocalDate>> keys = new HashMap<>();
        for (Bar b : base) {
            var s = slot(b.time());
            if (s == null || (noAsia && s.getKey() == Name.ASIA)) continue;
            String k = s.getValue() + "|" + s.getKey();
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(b);
            keys.put(k, s);
        }

        // 2. score each, using the previous session for sweeps and the last 10 of the same kind for "usual"
        Map<Name, Deque<double[]>> history = new EnumMap<>(Name.class); // {range, volume, path}
        for (Name n : Name.values()) history.put(n, new ArrayDeque<>());
        List<Session> out = new ArrayList<>();
        double prevH = Double.NaN, prevL = Double.NaN;
        for (var g : groups.entrySet()) {
            List<Bar> bars = g.getValue();
            Name name = keys.get(g.getKey()).getKey();
            LocalDate td = keys.get(g.getKey()).getValue();
            long[] w = window(name, td);
            double o = bars.get(0).open(), c = bars.get(bars.size() - 1).close();
            double h = Double.NEGATIVE_INFINITY, l = Double.POSITIVE_INFINITY, v = 0, path = 0;
            for (Bar b : bars) { h = Math.max(h, b.high()); l = Math.min(l, b.low()); v += b.volume(); path += b.high() - b.low(); }
            double r = h - l;

            Deque<double[]> hist = history.get(name);
            double refR = avg(hist, 0), refV = avg(hist, 1), refP = avg(hist, 2);
            if (Double.isNaN(refR) || refR <= 0) refR = r > 0 ? r : 1;
            boolean haveVol = v > 0 && refV > 0;
            double effort = haveVol ? v / refV : (refP > 0 ? path / refP : 1);
            double rangeRatio = r / refR;
            double body = Math.abs(c - o), bodyFrac = r > 0 ? body / r : 0;
            double clv = r > 0 ? (c - l) / r : 0.5;

            double acc = clamp((1.25 - rangeRatio) / 0.75) * (1 - 0.5 * bodyFrac) * clamp((2.5 - effort) / 1.5);
            double dist = clamp(bodyFrac / 0.7) * clamp(rangeRatio);
            double abs = clamp(effort - 1) * clamp(1 - (body / refR) / 0.5);

            double manHigh = 0, manLow = 0;
            if (!Double.isNaN(prevH) && r > 0) {
                if (h > prevH && c < prevH) manHigh = clamp((h - c) / r / 0.6);
                if (l < prevL && c > prevL) manLow = clamp((c - l) / r / 0.6);
            }
            double man = Math.max(manHigh, manLow);
            String sweep = manHigh == 0 && manLow == 0
                    ? (!Double.isNaN(prevH) && h > prevH && c >= prevH ? "Broke the prior high and held"
                    : !Double.isNaN(prevL) && l < prevL && c <= prevL ? "Broke the prior low and held" : null)
                    : manHigh >= manLow ? "Swept the prior high and closed back inside" : "Swept the prior low and closed back inside";

            Phase pa = new Phase("Accumulation", pct(acc), clv >= 0.5 ? "Bull" : "Bear");
            Phase pm = new Phase("Manipulation", pct(man), manHigh >= manLow ? "Bear" : "Bull");
            Phase pb = new Phase("Absorption", pct(abs), clv >= 0.5 ? "Bull" : "Bear");
            Phase pd = new Phase("Distribution", pct(dist), c >= o ? "Bull" : "Bear");

            // dominant: highest score; ties go manipulation > distribution > absorption > accumulation
            Phase top = pm;
            for (Phase p : List.of(pd, pb, pa)) if (p.pct() > top.pct()) top = p;
            boolean live = nowEpoch < w[1];
            out.add(new Session(name.label, td, w[0], w[1], live, bars.size(), o, h, l, c, v,
                    round(rangeRatio), round(effort), sweep, pa, pm, pb, pd,
                    top.dir() + " " + top.name(), top.dir(), top.pct()));

            if (!live && bars.size() >= 3) {
                hist.addLast(new double[]{r, v, path});
                if (hist.size() > REF_SESSIONS) hist.removeFirst();
            }
            prevH = h;
            prevL = l;
        }
        return out;
    }

    private static double avg(Deque<double[]> d, int i) {
        if (d.isEmpty()) return Double.NaN;
        double s = 0;
        for (double[] x : d) s += x[i];
        return s / d.size();
    }

    private static int pct(double v) { return (int) Math.round(clamp(v) * 100); }

    private static double clamp(double v) { return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v)); }

    private static double round(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.round(v * 100) / 100.0; }
}
