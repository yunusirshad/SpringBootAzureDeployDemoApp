package com.personalscreener.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * The market's bias during the session, read on the hour from 10:00 AM to 4:00 PM ET (+/-10).
 *
 * <ul>
 *   <li>SPY against its opening range (9:30-10:00): +2 above the high, -2 below the low</li>
 *   <li>SPY, QQQ and IWM against today's VWAP: +/-1 each</li>
 *   <li>SPY's change from the 9:30 open: +/-1 beyond 0.3%</li>
 *   <li>SPY's 15-minute trend: +1 above a rising 20 EMA, -1 below a falling one</li>
 *   <li>Breadth (Nasdaq + NYSE advancers / decliners): +1 at 1.5 or more, -1 at 0.67 or less</li>
 *   <li>VIX against its own open: -1 up 5% or more, +1 down 5% or more</li>
 *   <li>ES New York session phase (Sessions indicator): +/-1 for a Bull or Bear phase of 60% or more</li>
 * </ul>
 * The call uses the same cut-offs as the 9:30 call: +2 or more Bullish, -2 or less Bearish, else Neutral.
 */
public final class IntradayBias {

    public record Inputs(double spy, double orHigh, double orLow, double spyVwap, double qqq, double qqqVwap,
                         double iwm, double iwmVwap, double spyOpen, double spy15Close, double spy15Ema,
                         double spy15EmaBefore, Integer advancers, Integer decliners, double vix, double vixOpen,
                         SessionPhases.Session esNewYork) {}

    private IntradayBias() {}

    public static MarketBias.Result score(Inputs in) {
        List<MarketBias.Factor> f = new ArrayList<>();
        double p = in.spy();
        int or = Double.isNaN(p) || Double.isNaN(in.orHigh()) ? 0 : p > in.orHigh() ? 2 : p < in.orLow() ? -2 : 0;
        f.add(new MarketBias.Factor("SPY vs opening range", Double.isNaN(in.orHigh()) ? "n/a"
                : (or > 0 ? "above the high " : or < 0 ? "below the low " : "inside ") + fmt(in.orLow()) + "–" + fmt(in.orHigh()), or, 2));
        f.add(vwap("SPY vs VWAP", p, in.spyVwap()));
        f.add(vwap("QQQ vs VWAP", in.qqq(), in.qqqVwap()));
        f.add(vwap("IWM vs VWAP", in.iwm(), in.iwmVwap()));
        double chg = Double.isNaN(p) || Double.isNaN(in.spyOpen()) || in.spyOpen() <= 0 ? Double.NaN : (p / in.spyOpen() - 1) * 100;
        f.add(new MarketBias.Factor("SPY from the open", Double.isNaN(chg) ? "n/a" : String.format("%+.2f%%", chg),
                Double.isNaN(chg) ? 0 : chg >= 0.3 ? 1 : chg <= -0.3 ? -1 : 0, 1));
        double c15 = in.spy15Close(), e = in.spy15Ema(), eb = in.spy15EmaBefore();
        int t15 = 0;
        String r15 = "n/a";
        if (!Double.isNaN(c15) && !Double.isNaN(e) && !Double.isNaN(eb)) {
            boolean rising = e > eb;
            t15 = c15 > e && rising ? 1 : c15 < e && !rising ? -1 : 0;
            r15 = (c15 > e ? "above" : "below") + " a " + (rising ? "rising" : "falling") + " 20 EMA (" + fmt(e) + ")";
        }
        f.add(new MarketBias.Factor("SPY 15-minute trend", r15, t15, 1));
        Integer up = in.advancers(), dn = in.decliners();
        int br = 0;
        String rb = "n/a";
        if (up != null && dn != null && up + dn > 0) {
            double ratio = dn == 0 ? 99 : (double) up / dn;
            br = ratio >= 1.5 ? 1 : ratio <= 0.67 ? -1 : 0;
            rb = up + " up / " + dn + " down";
        }
        f.add(new MarketBias.Factor("Breadth (Nasdaq + NYSE)", rb, br, 1));
        double v = in.vix(), vo = in.vixOpen();
        double vchg = Double.isNaN(v) || Double.isNaN(vo) || vo <= 0 ? Double.NaN : (v / vo - 1) * 100;
        f.add(new MarketBias.Factor("VIX from its open", Double.isNaN(vchg) ? "n/a" : fmt(v) + String.format(" (%+.1f%%)", vchg),
                Double.isNaN(vchg) ? 0 : vchg >= 5 ? -1 : vchg <= -5 ? 1 : 0, 1));
        SessionPhases.Session s = in.esNewYork();
        f.add(s == null ? new MarketBias.Factor("ES New York session", "n/a", 0, 1)
                : new MarketBias.Factor("ES New York session", s.pct() + "% " + s.label() + (s.live() ? " (live)" : ""),
                s.pct() >= 60 ? ("Bull".equals(s.dir()) ? 1 : "Bear".equals(s.dir()) ? -1 : 0) : 0, 1));
        return MarketBias.fromFactors(f);
    }

    private static MarketBias.Factor vwap(String name, double price, double vwap) {
        if (Double.isNaN(price) || Double.isNaN(vwap)) return new MarketBias.Factor(name, "n/a", 0, 1);
        return new MarketBias.Factor(name, (price > vwap ? "above " : "below ") + fmt(vwap), price > vwap ? 1 : price < vwap ? -1 : 0, 1);
    }

    private static String fmt(double v) { return String.format("%.2f", v); }
}
