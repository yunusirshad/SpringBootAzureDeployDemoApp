package com.personalscreener.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * The market's bias at the 9:30 AM ET open, from what is known by then. Each factor scores points; the total
 * (at most +/-10) gives the call.
 *
 * <ul>
 *   <li>S&amp;P futures (ES) overnight, from the prior 4:00 PM ET: +/-2 beyond 0.3%, +/-1 beyond 0.1%</li>
 *   <li>Nasdaq futures (NQ) overnight: +/-1 beyond 0.3%</li>
 *   <li>SPY premarket gap against the prior close: +/-1 beyond 0.25%</li>
 *   <li>SPY's last close against its 20-day EMA: +/-1</li>
 *   <li>SPY's regime: +1 above its SMA200 with SMA50 over SMA200; -1 below its SMA200</li>
 *   <li>SPY's 5-day change: +/-1 beyond 1%</li>
 *   <li>VIX: -1 above 25 or 10% over its 10-day average; +1 under 16 and under its 10-day average</li>
 *   <li>ES London session phase (Sessions indicator): +/-1 for a Bull or Bear phase of 60% or more</li>
 *   <li>ES Asia session phase: the same</li>
 * </ul>
 * +2 or more is Bullish, -2 or less is Bearish, otherwise Neutral (with the lean). |score| of 6+ is strong,
 * 4-5 moderate, else weak.
 */
public final class MarketBias {

    /** Everything the call needs; NaN or null for what could not be read. */
    public record Inputs(double esOvernightPct, double nqOvernightPct, double spyGapPct,
                         double spyClose, double spyEma20, double spySma50, double spySma200, double spy5dPct,
                         double vix, double vixAvg10,
                         SessionPhases.Session esLondon, SessionPhases.Session esAsia) {}

    public record Factor(String name, String reading, int points, int max) {}

    public record Result(String bias, String strength, String lean, int score, int max, List<Factor> factors,
                         String summary) {}

    private MarketBias() {}

    public static Result score(Inputs in) {
        List<Factor> f = new ArrayList<>();
        double es = in.esOvernightPct();
        f.add(new Factor("S&P futures overnight", pct(es),
                Double.isNaN(es) ? 0 : es >= 0.3 ? 2 : es >= 0.1 ? 1 : es <= -0.3 ? -2 : es <= -0.1 ? -1 : 0, 2));
        double nq = in.nqOvernightPct();
        f.add(new Factor("Nasdaq futures overnight", pct(nq), Double.isNaN(nq) ? 0 : nq >= 0.3 ? 1 : nq <= -0.3 ? -1 : 0, 1));
        double gap = in.spyGapPct();
        f.add(new Factor("SPY premarket gap", pct(gap), Double.isNaN(gap) ? 0 : gap >= 0.25 ? 1 : gap <= -0.25 ? -1 : 0, 1));

        double c = in.spyClose(), ema = in.spyEma20();
        f.add(new Factor("SPY vs 20-day EMA", Double.isNaN(c) || Double.isNaN(ema) ? "n/a"
                : (c > ema ? "above" : "below") + " (" + fmt(c) + " vs " + fmt(ema) + ")",
                Double.isNaN(c) || Double.isNaN(ema) ? 0 : c > ema ? 1 : -1, 1));

        double s50 = in.spySma50(), s200 = in.spySma200();
        int regime = Double.isNaN(c) || Double.isNaN(s200) ? 0 : c < s200 ? -1 : (!Double.isNaN(s50) && s50 > s200 ? 1 : 0);
        f.add(new Factor("SPY regime (SMA200)", Double.isNaN(s200) ? "n/a"
                : (c > s200 ? "above" : "below") + " SMA200 " + fmt(s200) + (Double.isNaN(s50) ? "" : ", SMA50 " + (s50 > s200 ? "over" : "under") + " SMA200"),
                regime, 1));

        double m5 = in.spy5dPct();
        f.add(new Factor("SPY 5-day change", pct(m5), Double.isNaN(m5) ? 0 : m5 >= 1 ? 1 : m5 <= -1 ? -1 : 0, 1));

        double vix = in.vix(), va = in.vixAvg10();
        int vixPts = 0;
        if (!Double.isNaN(vix)) {
            boolean rising = !Double.isNaN(va) && vix > va * 1.10;
            boolean falling = !Double.isNaN(va) && vix < va;
            vixPts = vix > 25 || rising ? -1 : vix < 16 && falling ? 1 : 0;
        }
        f.add(new Factor("VIX", Double.isNaN(vix) ? "n/a" : fmt(vix) + (Double.isNaN(va) ? "" : " (10-day avg " + fmt(va) + ")"), vixPts, 1));

        f.add(session("ES London session", in.esLondon()));
        f.add(session("ES Asia session", in.esAsia()));

        return fromFactors(f);
    }

    /** The call from a list of scored factors: +2 or more Bullish, -2 or less Bearish, else Neutral. */
    public static Result fromFactors(List<Factor> f) {
        int score = f.stream().mapToInt(Factor::points).sum();
        int max = f.stream().mapToInt(Factor::max).sum();
        String bias = score >= 2 ? "Bullish" : score <= -2 ? "Bearish" : "Neutral";
        int a = Math.abs(score);
        String strength = bias.equals("Neutral") ? "unclear" : a >= 6 ? "strong" : a >= 4 ? "moderate" : "weak";
        String lean = score > 0 ? "bullish" : score < 0 ? "bearish" : "none";
        String summary = bias.equals("Neutral")
                ? "Neutral" + (score == 0 ? "" : ", leaning " + lean) + " (" + signed(score) + " of ±" + max + ")"
                : bias + ", " + strength + " (" + signed(score) + " of ±" + max + ")";
        return new Result(bias, strength, lean, score, max, f, summary);
    }

    private static Factor session(String name, SessionPhases.Session s) {
        if (s == null) return new Factor(name, "n/a", 0, 1);
        int pts = s.pct() >= 60 ? ("Bull".equals(s.dir()) ? 1 : "Bear".equals(s.dir()) ? -1 : 0) : 0;
        return new Factor(name, s.pct() + "% " + s.label() + (s.live() ? " (live)" : ""), pts, 1);
    }

    private static String pct(double v) { return Double.isNaN(v) ? "n/a" : String.format("%+.2f%%", v); }

    private static String fmt(double v) { return String.format("%.2f", v); }

    private static String signed(int v) { return (v > 0 ? "+" : "") + v; }
}
