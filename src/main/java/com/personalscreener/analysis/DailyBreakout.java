package com.personalscreener.analysis;

import com.personalscreener.data.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * The Core list's "Buy now" idea on daily bars, so it can be backtested over years: in a Strong Uptrend or Early
 * Bullish Reversal (as of the prior close), buy when the price trades above the prior day's high (a buy stop; the
 * open when it gaps above). Stop: the higher of the prior day's low and entry - 1 ATR(14). Target: entry + 1.5R.
 * Held up to {@code holdDays}; from the day after entry the stop moves to breakeven once the trade has been up 1R.
 * On a day that reaches both the stop and the target, the stop is assumed (daily bars cannot tell the order).
 */
public final class DailyBreakout {

    public record Trade(String entryDate, double entry, double stop, double target, String exitDate, double exit,
                        String exitReason, int days, double rMultiple, double returnPct) {
        public boolean win() { return returnPct > 0; }
    }

    private DailyBreakout() {}

    public static List<Trade> backtest(List<Bar> d, double targetR, int holdDays, double costBps) {
        return backtest(d, targetR, holdDays, costBps, false);
    }

    /**
     * {@code useMagnet}: with daily magnet zones (20 days, 3 months, 1 year of daily volume profiles, from data before
     * the day), skip a trade when a zone starts between the entry and the target (supply in the way), and move a stop
     * that falls inside a zone to just under it.
     */
    public static List<Trade> backtest(List<Bar> d, double targetR, int holdDays, double costBps, boolean useMagnet) {
        List<Trade> out = new ArrayList<>();
        int n = d.size();
        if (n < 215) return out;
        double[] c = Indicators.closes(d);
        double[] atr = QuantumShift.atrSeries(d, 14);
        double cost = costBps / 10_000;
        int i = 201;
        while (i < n) {
            String tc = TrendClassifier.classify(java.util.Arrays.copyOf(c, i)).trendClass();
            Bar prev = d.get(i - 1), b = d.get(i);
            boolean up = TrendClassifier.STRONG.equals(tc) || TrendClassifier.BULL_REV.equals(tc);
            if (!up || !(b.high() > prev.high()) || Double.isNaN(atr[i - 1])) { i++; continue; }
            double entry = Math.max(b.open(), prev.high() + 0.01);
            double stop = Math.max(prev.low(), entry - atr[i - 1]);
            if (!(stop < entry)) { i++; continue; }
            if (useMagnet) {
                List<MagnetZones.Zone> zs = MagnetZones.confluence(MagnetZones.dailyProfiles(d.subList(0, i)));
                for (MagnetZones.Zone z : zs) if (z.low() < stop && stop < z.high() && z.high() < entry) stop = z.low() - 0.1 * atr[i - 1];
                double tgt = entry + targetR * (entry - stop);
                boolean blocked = false;
                for (MagnetZones.Zone z : zs) if (z.low() > entry && z.low() < tgt) blocked = true;
                if (blocked) { i++; continue; }
            }
            double risk = entry - stop, target = entry + targetR * risk;
            int k = i;
            double exit;
            String why;
            boolean be = false;
            while (true) {
                Bar x = d.get(k);
                double curStop = be ? entry : stop;
                if (x.low() <= curStop) {
                    // entry day: filled at the stop; a later day can gap through it and fill at the open
                    exit = k == i ? curStop : Math.min(x.open(), curStop);
                    why = be ? "BREAKEVEN" : "STOP";
                    break;
                }
                if (x.high() >= target) { exit = k == i ? target : Math.max(x.open(), target); why = "TARGET"; break; }
                if (k - i + 1 >= holdDays || k == n - 1) { exit = x.close(); why = k == n - 1 && k - i + 1 < holdDays ? "END OF DATA" : "TIME EXIT"; break; }
                if (!be && x.high() >= entry + risk) be = true;
                k++;
            }
            double ret = (exit / entry - 1) - 2 * cost;
            out.add(new Trade(PvConfirm.date(b).toString(), entry, stop, target, PvConfirm.date(d.get(k)).toString(), exit, why,
                    k - i + 1, (exit - entry) / risk, ret * 100));
            i = k + 1;   // one trade at a time
        }
        return out;
    }
}
