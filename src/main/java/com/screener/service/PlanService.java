package com.screener.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.screener.config.AppConfig;
import com.screener.config.ScreenerProperties;
import com.screener.data.MarketData;
import com.screener.model.Bar;
import com.screener.model.TradePlan;

/**
 * Builds a long or short plan from the daily levels and sizes the shares from the account and the stop.
 *
 * Levels come from the last completed daily bar: prior high (PDH), low (PDL), close (PDC), floor pivot
 * P = (H+L+C)/3, R1 = 2P - L, S1 = 2P - H, plus ATR(14) and the 20-day average of closes.
 *
 * Bias: price above both P and the 20-day average = BULLISH (LONG); below both = BEARISH (SHORT);
 * otherwise NEUTRAL (no trade unless a side is asked for).
 *
 * LONG:  entry PDH + 0.01; stop P (or PDL if P is not below the entry), never more than 1 ATR away;
 *        target1 R1 when it is at least 0.5R above the entry, else entry + 1R; target2 entry + 2R.
 * SHORT: the mirror: entry PDL - 0.01, stop P (or PDH), target1 S1 or entry - 1R, target2 entry - 2R.
 *
 * Shares = floor(account x risk% / |entry - stop|), capped so the position fits in the account.
 */
@Service
public class PlanService {

    private final MarketData market;
    private final ScreenerProperties props;
    private final Clock clock;

    public PlanService(MarketData market, ScreenerProperties props, Clock clock) {
        this.market = market;
        this.props = props;
        this.clock = clock;
    }

    public TradePlan analyze(String symbol, Double account, Double riskPercent, String side) {
        List<Bar> bars = market.dailyBars(symbol, 40);
        double acct = account != null && account > 0 ? account : props.getAccount().getSize();
        double risk = riskPercent != null && riskPercent > 0 ? riskPercent : props.getAccount().getRiskPercent();
        return build(symbol, bars, LocalDate.now(clock.withZone(AppConfig.NEW_YORK)), acct, risk, side);
    }

    public static TradePlan build(String symbol, List<Bar> bars, LocalDate today, double account, double riskPct, String sideOverride) {
        if (bars.size() < 21) throw new IllegalArgumentException("Need at least 21 daily bars for " + symbol + ", got " + bars.size());
        List<String> notes = new ArrayList<>();
        Bar last = bars.get(bars.size() - 1);
        boolean lastIsToday = last.time().atZone(AppConfig.NEW_YORK).toLocalDate().equals(today);
        List<Bar> done = lastIsToday ? bars.subList(0, bars.size() - 1) : bars;
        Bar prior = done.get(done.size() - 1);
        double price = last.close();
        if (lastIsToday) notes.add("Price is today's last trade; levels are from " + date(prior) + ".");
        else notes.add("Levels and price are from the last close (" + date(prior) + ").");

        double h = prior.high(), l = prior.low(), c = prior.close();
        double p = (h + l + c) / 3;
        double r1 = 2 * p - l, s1 = 2 * p - h;
        double atr = atr(done, 14);
        double sma = 0;
        for (int i = done.size() - 20; i < done.size(); i++) sma += done.get(i).close();
        sma /= 20;

        String bias = price > p && price > sma ? "BULLISH" : price < p && price < sma ? "BEARISH" : "NEUTRAL";
        String side = sideOverride == null || sideOverride.isBlank() ? null : sideOverride.toUpperCase(Locale.ROOT);
        if (side == null) side = switch (bias) { case "BULLISH" -> "LONG"; case "BEARISH" -> "SHORT"; default -> "NONE"; };
        else if (!side.equals("LONG") && !side.equals("SHORT")) throw new IllegalArgumentException("side must be long or short");

        if (side.equals("NONE")) {
            notes.add("Bias is neutral (price between the pivot and the 20-day average): no plan. Ask for side=long or side=short to see one.");
            return new TradePlan(symbol, prior.time().atZone(AppConfig.NEW_YORK).toLocalDate(), "NONE", bias, "NO_TRADE", r(price),
                    h, l, c, r(p), r(r1), r(s1), r(atr), r(sma), null, null, null, null, null, 0, account, riskPct, 0, 0, notes);
        }

        boolean longSide = side.equals("LONG");
        double entry, stop, t1, t2, rps;
        if (longSide) {
            entry = r(h + 0.01);
            stop = p < entry ? p : l;
            if (atr > 0 && entry - stop > atr) { stop = entry - atr; notes.add("Stop pulled to 1 ATR under the entry."); }
            stop = floor2(stop);
            rps = r(entry - stop);
            t1 = r1 >= entry + 0.5 * rps ? r(r1) : r(entry + rps);
            t2 = r(entry + 2 * rps);
        } else {
            entry = r(l - 0.01);
            stop = p > entry ? p : h;
            if (atr > 0 && stop - entry > atr) { stop = entry + atr; notes.add("Stop pulled to 1 ATR over the entry."); }
            stop = ceil2(stop);
            rps = r(stop - entry);
            t1 = s1 <= entry - 0.5 * rps ? r(s1) : r(entry - rps);
            t2 = r(entry - 2 * rps);
        }
        if (rps <= 0) throw new IllegalStateException("Stop equals entry for " + symbol + "; no plan.");
        if (!bias.equals(longSide ? "BULLISH" : "BEARISH")) notes.add("Side " + side + " is against the " + bias + " bias.");

        String state;
        if (longSide) state = price >= entry ? "TRIGGERED" : price < stop ? "INVALID" : "WAITING";
        else state = price <= entry ? "TRIGGERED" : price > stop ? "INVALID" : "WAITING";

        double riskBudget = account * riskPct / 100.0;
        int byRisk = (int) Math.floor(riskBudget / rps);
        int byCash = (int) Math.floor(account / entry);
        int shares = Math.max(0, Math.min(byRisk, byCash));
        if (byCash < byRisk) notes.add("Shares capped by the account size, not the risk.");
        if (shares == 0) notes.add("The stop is too wide for this account and risk: zero shares.");
        if (!longSide) notes.add("Short: needs shares to borrow and a margin account.");

        return new TradePlan(symbol, prior.time().atZone(AppConfig.NEW_YORK).toLocalDate(), side, bias, state, r(price),
                h, l, c, r(p), r(r1), r(s1), r(atr), r(sma), entry, stop, t1, t2, rps, shares, account, riskPct,
                r(shares * rps), r(shares * entry), notes);
    }

    static double atr(List<Bar> bars, int n) {
        int start = Math.max(1, bars.size() - n);
        double sum = 0;
        int count = 0;
        for (int i = start; i < bars.size(); i++) {
            Bar b = bars.get(i);
            double pc = bars.get(i - 1).close();
            sum += Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - pc), Math.abs(b.low() - pc)));
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private static String date(Bar b) {
        return b.time().atZone(AppConfig.NEW_YORK).toLocalDate().toString();
    }

    static double r(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double floor2(double v) {
        return Math.floor(v * 100.0 + 1e-9) / 100.0;
    }

    private static double ceil2(double v) {
        return Math.ceil(v * 100.0 - 1e-9) / 100.0;
    }
}
