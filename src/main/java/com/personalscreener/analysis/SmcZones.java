package com.personalscreener.analysis;

import com.personalscreener.data.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fair value gaps (FVG) and order blocks (OB) on any timeframe.
 *
 * <ul>
 *   <li><b>Bullish FVG</b>: three candles where the first candle's high is below the third candle's low; the zone is
 *       the gap between them. <b>Bearish FVG</b>: the first candle's low is above the third candle's high.</li>
 *   <li><b>Bullish OB</b>: the last down candle (close below open) before a displacement up: a close within the next
 *       3 candles at least 1 ATR above that candle's high. The zone is the candle's low to high. <b>Bearish OB</b> is
 *       the mirror.</li>
 *   <li>Gaps smaller than 0.1 ATR are ignored. A bullish zone is <b>mitigated</b> (gone) once a later candle closes below
 *       its low; a bearish zone once a later candle closes above its high.</li>
 * </ul>
 */
public final class SmcZones {

    public enum Kind { FVG, OB }

    public record Zone(Kind kind, boolean bullish, double low, double high, long time, String timeframe) {
        public boolean overlaps(Zone o) { return low <= o.high() && o.low() <= high; }
        public String label() { return (bullish ? "bullish " : "bearish ") + timeframe + " " + kind; }
    }

    public static final double MIN_GAP_ATR = 0.1, DISPLACEMENT_ATR = 1.0;

    private SmcZones() {}

    /**
     * Zones formed in the last {@code lookback} bars that are still unmitigated at the last bar, newest first.
     *
     * @param bars completed bars, oldest first
     * @param atr  the timeframe's ATR at the last bar
     */
    public static List<Zone> active(List<Bar> bars, double atr, int lookback, String timeframe) {
        List<Zone> out = new ArrayList<>();
        int n = bars.size();
        if (n < 5 || !(atr > 0)) return out;
        for (int i = Math.max(2, n - lookback); i < n; i++) {
            Bar a = bars.get(i - 2), c = bars.get(i);
            if (c.low() - a.high() >= MIN_GAP_ATR * atr) add(out, bars, new Zone(Kind.FVG, true, a.high(), c.low(), c.time(), timeframe), i);
            if (a.low() - c.high() >= MIN_GAP_ATR * atr) add(out, bars, new Zone(Kind.FVG, false, c.high(), a.low(), c.time(), timeframe), i);
        }
        for (int j = Math.max(0, n - lookback); j < n - 1; j++) {
            Bar o = bars.get(j);
            int end = Math.min(n - 1, j + 3);
            // the zone forms on the candle that completes the displacement; later closes can mitigate it
            if (o.close() < o.open()) {
                for (int k = j + 1; k <= end; k++) {
                    if (bars.get(k).close() - o.high() >= DISPLACEMENT_ATR * atr) {
                        add(out, bars, new Zone(Kind.OB, true, o.low(), o.high(), o.time(), timeframe), k);
                        break;
                    }
                }
            } else if (o.close() > o.open()) {
                for (int k = j + 1; k <= end; k++) {
                    if (o.low() - bars.get(k).close() >= DISPLACEMENT_ATR * atr) {
                        add(out, bars, new Zone(Kind.OB, false, o.low(), o.high(), o.time(), timeframe), k);
                        break;
                    }
                }
            }
        }
        out.sort(Comparator.comparingLong(Zone::time).reversed());
        return out;
    }

    /** Adds the zone unless a candle after {@code formedAt} has closed through it. */
    private static void add(List<Zone> out, List<Bar> bars, Zone z, int formedAt) {
        for (int k = formedAt + 1; k < bars.size(); k++) {
            double c = bars.get(k).close();
            if (z.bullish() ? c < z.low() : c > z.high()) return;
        }
        out.add(z);
    }
}
