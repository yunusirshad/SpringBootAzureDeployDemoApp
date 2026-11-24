package com.personalscreener.service;

import com.personalscreener.analysis.*;
import com.personalscreener.data.Bar;
import com.personalscreener.data.YahooClient;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.StockRow;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * One stock's playbook: every strategy in the app backtested on that stock's own history, what each says right now,
 * and a trading plan from the best strategy that has an edge on this stock and is active now (or "no trade").
 *
 * <p>Strategies: the daily <b>Breakout</b> (the Core list's Buy now idea, 2 years of daily bars), <b>PV Confirm</b>
 * (2 years of daily bars), the <b>trading plan bot</b> as a swing and a day trade (60 days of 5-minute data) and
 * <b>Quantum Shift</b> long only (60 days of hourly bars). A strategy has an <b>edge</b> on the stock with at least 8
 * trades, a profit factor of 1.3 or more and a positive average trade; <b>weak</b> with a profit factor of 1 to 1.3;
 * otherwise <b>no edge</b> (or <b>too few trades</b>).
 */
@Service
public class PlaybookService {

    /** The trader's account and risk rules, saved to profile.json. */
    public static class Profile {
        public double account = 9000;
        public double riskPct = 2;
        public double minRR = 2;

        public double riskDollars() { return account * riskPct / 100; }

        public void validate() {
            if (!(account > 0)) throw new IllegalArgumentException("Account size must be above 0");
            if (!(riskPct > 0 && riskPct <= 10)) throw new IllegalArgumentException("Risk must be above 0% and at most 10%");
            if (!(minRR >= 1 && minRR <= 10)) throw new IllegalArgumentException("Minimum R:R must be 1 to 10");
        }
    }

    private final ProfileService profiles;

    public Profile profile() { return profiles.get(); }

    public Profile saveProfile(Profile p) { return profiles.save(p); }

    static final int MIN_TRADES = 8;
    static final double EDGE_PF = 1.3;

    private final YahooClient yahoo;
    private final ScreenService screen;
    private final PvService pv;
    private final PlanService plan;
    private final MarketClock clock;

    public PlaybookService(YahooClient yahoo, ScreenService screen, PvService pv, PlanService plan, MarketClock clock,
                           ProfileService profiles) {
        this.profiles = profiles;
        this.yahoo = yahoo;
        this.screen = screen;
        this.pv = pv;
        this.plan = plan;
        this.clock = clock;
    }

    public Map<String, Object> build(String symbol, Double riskOverride) throws Exception {
        Profile pr = profiles.get();
        double minRR = pr.minRR;
        Double riskDollars = riskOverride != null ? riskOverride : pr.riskDollars();
        double account = pr.account;
        ZonedDateTime now = clock.now();
        LocalDate today = now.toLocalDate();
        StockRow row = screen.snapshot().rows.stream().filter(r -> r.symbol.equals(symbol)).findFirst().orElse(null);

        List<Bar> daily = yahoo.bars(symbol, "1d", "2y");
        List<Bar> done = daily.stream().filter(b -> PvConfirm.date(b).isBefore(today)
                || !clock.isTradingDay(today) || !now.toLocalTime().isBefore(LocalTime.of(16, 15))).toList();
        List<Bar> spyDaily = yahoo.bars("SPY", "1d", "2y");
        List<Bar> h1 = yahoo.bars(symbol, "60m", "1y"), m15 = yahoo.bars(symbol, "15m", "60d"), m5 = yahoo.bars(symbol, "5m", "60d");
        List<Bar> spy15 = yahoo.bars("SPY", "15m", "60d");

        List<Map<String, Object>> strategies = new ArrayList<>();
        List<Map<String, Object>> candidates = new ArrayList<>();
        double price = row != null && row.price != null ? row.price : done.get(done.size() - 1).close();
        double atrD = Indicators.atr(done, 14);

        // 1. daily breakout (the Core Buy now idea)
        double boR = Math.max(1.5, minRR);
        List<DailyBreakout.Trade> bo = DailyBreakout.backtest(done, boR, 5, 5);
        Map<String, Object> boS = summary("Breakout (Core Buy now)", String.format("2 years of daily bars: buy over the prior day high in an uptrend, stop at the prior low or 1 ATR, target %.1fR, up to 5 days", boR),
                bo.stream().map(DailyBreakout.Trade::returnPct).toList(), bo.stream().map(DailyBreakout.Trade::rMultiple).toList(),
                bo.stream().map(t -> t.entryDate() + " → " + t.exitReason() + String.format(" %+.1f%%", t.returnPct())).toList());
        String sig = row == null ? null : row.signal;
        boolean boLive = SetupScore.BUY_NOW.equals(sig) || SetupScore.READY.equals(sig);
        Bar prevDay = done.get(done.size() - 1);
        double boTrigger = row != null && row.breakoutLevel != null ? row.breakoutLevel : prevDay.high();
        double boEntry = Math.max(price, boTrigger + 0.01);
        double boStop = Math.max(prevDay.low(), boEntry - atrD);
        boS.put("now", row == null ? "Not on the screen" : "Core signal: " + signalLabel(sig) + (row.signalNotes.isEmpty() ? "" : " (" + String.join(", ", row.signalNotes) + ")"));
        boS.put("active", boLive);
        strategies.add(boS);
        if (boLive && boStop < boEntry) {
            candidates.add(plan("Breakout (Core Buy now)", boS, price >= boTrigger ? "Buy now (through the trigger)" : String.format("Buy stop at %.2f (over the breakout level)", boEntry),
                    boEntry, boStop, boEntry + boR * (boEntry - boStop), String.format("%.1fR; hold up to 5 trading days, breakeven after +1R", boR), riskDollars, account));
        }

        // 1b. the same breakout with the daily magnet zones (skip when a zone is in the way, stops under zones)
        List<DailyBreakout.Trade> bm = DailyBreakout.backtest(done, boR, 5, 5, true);
        Map<String, Object> bmS = summary("Breakout + magnet zones", String.format("As the Breakout, but skipped when a daily magnet zone (20 days, 3 months, 1 year) starts before the %.1fR target, and a stop inside a zone moves under it", boR),
                bm.stream().map(DailyBreakout.Trade::returnPct).toList(), bm.stream().map(DailyBreakout.Trade::rMultiple).toList(),
                bm.stream().map(t -> t.entryDate() + " → " + t.exitReason() + String.format(" %+.1f%%", t.returnPct())).toList());
        List<MagnetZones.Zone> zonesNow = MagnetZones.confluence(MagnetZones.dailyProfiles(done));
        double bmStop = boStop;
        for (MagnetZones.Zone z : zonesNow) if (z.low() < bmStop && bmStop < z.high() && z.high() < boEntry) bmStop = z.low() - 0.1 * atrD;
        double bmTarget = boEntry + boR * (boEntry - bmStop);
        MagnetZones.Zone inWay = zonesNow.stream().filter(z -> z.low() > boEntry && z.low() < bmTarget).min(Comparator.comparingDouble(MagnetZones.Zone::low)).orElse(null);
        bmS.put("now", (inWay == null ? "No magnet zone between the entry and the target" : String.format("Magnet zone ×%d at %.2f-%.2f is in the way", inWay.score(), inWay.low(), inWay.high()))
                + "; " + boS.get("now"));
        bmS.put("active", boLive && inWay == null);
        strategies.add(bmS);
        if (boLive && inWay == null && bmStop < boEntry) {
            candidates.add(plan("Breakout + magnet zones", bmS, price >= boTrigger ? "Buy now (through the trigger)" : String.format("Buy stop at %.2f (over the breakout level)", boEntry),
                    boEntry, bmStop, bmTarget, String.format("%.1fR; hold up to 5 trading days, breakeven after +1R; the stop sits under the magnet zone", boR), riskDollars, account));
        }

        // 2. PV Confirm
        Map<LocalDate, Boolean> spyAbove = PvConfirm.spyAbove(spyDaily.stream().filter(b -> done.isEmpty() || !PvConfirm.date(b).isAfter(PvConfirm.date(done.get(done.size() - 1)))).toList());
        PvConfirm.Settings ps = pv.settings();
        List<PvConfirm.Trade> pvt = PvConfirm.backtest(done, spyAbove, ps).stream().filter(t -> t.exit() != null).toList();
        Map<String, Object> pvS = summary("PV Confirm", "2 years of daily bars: RSI(2) dip in an uptrend, exit on the first profitable close, the stop or 10 bars",
                pvt.stream().map(t -> t.returnPct() == null ? 0.0 : t.returnPct()).toList(),
                pvt.stream().map(t -> (t.exit() - t.entry()) / Math.max(1e-9, t.entry() - t.stop())).toList(),
                pvt.stream().map(t -> t.entryDate() + " → " + t.exitReason() + String.format(" %+.1f%%", t.returnPct())).toList());
        PvService.Row pvRow = pv.state().rows.stream().filter(r -> r.symbol.equals(symbol)).findFirst().orElse(null);
        PvConfirm.Result pvNow = PvConfirm.evaluate(done, null, spyAbove, ps);
        pvS.put("now", pvRow != null ? pvRow.status + (pvRow.note == null ? "" : " — " + pvRow.note) : pvNow.status() + " — " + pvNow.note());
        String pvStatus = pvRow != null ? pvRow.status : pvNow.status().name();
        boolean pvLive = "BUY".equals(pvStatus) || "READY_TO_BUY".equals(pvStatus);
        pvS.put("active", pvLive);
        strategies.add(pvS);
        if (pvLive) {
            double e, st, tg;
            String how;
            if ("BUY".equals(pvStatus)) { e = pvNow.entry(); st = pvNow.stop(); tg = pvNow.entry(); how = "Buy at the next open (estimated from the close)"; }
            else { var ex = pvNow.expected(); e = ex.buy(); st = ex.stop(); tg = ex.target(); how = String.format("Wait for a close at or under %.2f (RSI(2) under %.0f), then buy the next open", ex.buy(), ps.rsiBuyBelow); }
            candidates.add(plan("PV Confirm", pvS, how, e, st, Math.max(tg, e + 0.01), "the first close above the entry (target shown is the 5-day average); exit by bar " + ps.maxBars, riskDollars, account));
        }

        // 3. trading plan bot (swing and day)
        TradePlan.Bars tb = new TradePlan.Bars(daily, h1, m15, m5, spy15);
        TradePlan.Settings ts = copy(plan.settings(), plan.settings().holdDays);
        ts.minRR = Math.max(ts.minRR, minRR);
        for (int hold : new int[]{ts.holdDays, 1}) {
            if (hold == 1 && ts.holdDays == 1) continue;
            TradePlan.Settings s2 = copy(ts, hold);
            List<TradePlan.Trade> tt = TradePlan.backtest(symbol, tb, s2);
            String name = hold == 1 ? "Trading plan bot, day trade" : "Trading plan bot, swing (" + hold + " days)";
            Map<String, Object> tS = summary(name, "60 days of 5-minute data: FVG / order block / Fibonacci / reversal / breakout setups with a 5m confirmation",
                    tt.stream().map(TradePlan.Trade::returnPct).toList(), tt.stream().map(TradePlan.Trade::rMultiple).toList(),
                    tt.stream().map(t -> Instant.ofEpochSecond(t.entryTime()).atZone(MarketClock.ET).toLocalDate() + " " + t.type() + "-" + t.grade() + " → " + t.exitReason() + String.format(" %+.1f%%", t.returnPct())).toList());
            TradePlan.Look look = TradePlan.evaluate(tb, now.toEpochSecond(), s2, row != null && row.gex != null ? row.gex.callWall() : Double.NaN);
            tS.put("now", look.setup() != null ? String.format("%s grade %s setup: entry %.2f, stop %.2f, target %.2f (%s)", look.setup().type(), look.setup().grade(),
                    look.setup().entry(), look.setup().stop(), look.setup().target(), look.setup().targetSource())
                    : look.watch() != null ? look.watch() : look.reason());
            tS.put("active", look.setup() != null);
            strategies.add(tS);
            if (look.setup() != null) {
                var st = look.setup();
                candidates.add(plan(name, tS, "Buy now (5m confirmation in)", price, price - (st.entry() - st.stop()), price + (st.target() - st.entry()),
                        st.targetSource() + (hold == 1 ? "; out by 3:45 PM" : "; hold up to " + hold + " days") + ", breakeven after +1R", riskDollars, account));
            }
        }

        // 4. Quantum Shift, long only (1h)
        long nowSec = now.toEpochSecond();
        List<Bar> h1done = h1.stream().filter(b -> b.time() + 3600 <= nowSec).toList();
        if (h1done.size() >= 120) {
            var p = new BacktestService.Params(60, "60m", 60, false, 2.0, 2.5, 35, 5);
            BacktestService.StockResult qs = BacktestService.simulate(symbol, h1done, p);
            Map<String, Object> qS = summary("Quantum Shift (1h, long only)", "60 days of hourly bars: energy 60+, entry next open, 2 ATR stop, 2.5 ATR trail, 35 bars",
                    qs.tradeList().stream().map(BacktestService.Trade::returnPct).toList(), qs.tradeList().stream().map(BacktestService.Trade::r).toList(),
                    qs.tradeList().stream().map(t -> t.entryTime() + " → " + t.exitReason() + String.format(" %+.1f%%", t.returnPct())).toList());
            QuantumShift.Series ser = QuantumShift.compute(h1done, 60);
            int last = h1done.size() - 1, since = -1;
            for (int i = last; i >= Math.max(0, last - 6); i--) if (ser.signal()[i] == 1) { since = last - i; break; }
            boolean qsLive = ser.regime()[last] == 1 && since >= 0;
            qS.put("now", String.format("Regime %s, energy %.0f%s", ser.regime()[last] > 0 ? "up" : ser.regime()[last] < 0 ? "down" : "neutral",
                    ser.energy()[last], since >= 0 ? ", shifted up " + since + " hours ago" : ""));
            qS.put("active", qsLive);
            strategies.add(qS);
            if (qsLive) {
                double a = ser.atr()[last];
                candidates.add(plan("Quantum Shift (1h, long only)", qS, "Buy at the next hourly open", price, price - 2 * a, price + 2 * a * minRR,
                        String.format("trail 2.5 ATR(1h) off the best close; exit on a shift down or after 35 hours (target shown at %.1fR as a guide)", minRR), riskDollars, account));
            }
        }

        // the plan: the best active strategy with an edge on this stock
        candidates.sort(Comparator.comparingDouble((Map<String, Object> c) -> -num(((Map<?, ?>) c.get("record")).get("profitFactor"))));
        for (Map<String, Object> c : candidates) {
            Object rr = c.get("rr");
            boolean ok = rr instanceof Number n && n.doubleValue() >= minRR - 1e-9;
            c.put("meetsRR", ok);
            if (!ok) c.put("rejected", String.format("R:R %s is under your 1:%.1f minimum", rr == null ? "n/a" : String.format("%.2f", ((Number) rr).doubleValue()), minRR));
        }
        Map<String, Object> best = candidates.stream().filter(c -> "edge".equals(((Map<?, ?>) c.get("record")).get("verdict")) && Boolean.TRUE.equals(c.get("meetsRR")))
                .findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("name", row == null ? null : row.name);
        out.put("price", price);
        out.put("generatedAt", OffsetDateTime.now().toString());
        out.put("context", context(row));
        out.put("strategies", strategies);
        out.put("candidates", candidates);
        out.put("plan", best);
        out.put("verdict", best != null ? "TRADE"
                : candidates.isEmpty() ? "NO TRADE: no strategy is active on " + symbol + " right now"
                : "NO TRADE on " + symbol + ": " + String.join("; ", candidates.stream().map(c -> c.get("strategy") + " is active but "
                + (!"edge".equals(((Map<?, ?>) c.get("record")).get("verdict")) ? verdictText(String.valueOf(((Map<?, ?>) c.get("record")).get("verdict"))) : c.get("rejected"))).toList()));
        out.put("riskDollars", riskDollars);
        out.put("profile", pr);
        return out;
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    static Map<String, Object> summary(String name, String test, List<Double> rets, List<Double> rs, List<String> lines) {
        int n = rets.size();
        long wins = rets.stream().filter(r -> r > 0).count();
        double gain = rets.stream().filter(r -> r > 0).mapToDouble(Double::doubleValue).sum();
        double loss = -rets.stream().filter(r -> r <= 0).mapToDouble(Double::doubleValue).sum();
        double avg = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double comp = 1;
        for (double r : rets) comp *= 1 + r / 100;
        Double pf;   // no losses: 99 when there were gains, none when there were no trades
        if (loss > 0) pf = Math.round(100 * gain / loss) / 100.0;
        else pf = gain > 0 ? Double.valueOf(99) : null;
        String verdict = n < MIN_TRADES ? "too few trades" : pf != null && pf >= EDGE_PF && avg > 0 ? "edge" : pf != null && pf >= 1 ? "weak" : "no edge";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategy", name);
        m.put("test", test);
        m.put("trades", n);
        m.put("winRate", n == 0 ? null : Math.round(1000.0 * wins / n) / 10.0);
        m.put("profitFactor", pf);
        m.put("avgReturnPct", n == 0 ? null : Math.round(avg * 100) / 100.0);
        m.put("avgR", n == 0 ? null : Math.round(100 * rs.stream().mapToDouble(Double::doubleValue).average().orElse(0)) / 100.0);
        m.put("totalReturnPct", Math.round((comp - 1) * 10_000) / 100.0);
        m.put("verdict", verdict);
        List<String> recent = new ArrayList<>(lines.subList(Math.max(0, lines.size() - 5), lines.size()));
        Collections.reverse(recent);
        m.put("recent", recent);
        return m;
    }

    static Map<String, Object> plan(String strategy, Map<String, Object> record, String how, double entry, double stop, double target,
                                    String exit, Double riskDollars) {
        return plan(strategy, record, how, entry, stop, target, exit, riskDollars, Double.NaN);
    }

    /** {@code account}: the position never costs more than this (no margin); NaN for no cap. */
    static Map<String, Object> plan(String strategy, Map<String, Object> record, String how, double entry, double stop, double target,
                                    String exit, Double riskDollars, double account) {
        Map<String, Object> m = new LinkedHashMap<>();
        double risk = entry - stop;
        m.put("strategy", strategy);
        m.put("record", record);
        m.put("how", how);
        m.put("entry", r2(entry));
        m.put("stop", r2(stop));
        m.put("target", r2(target));
        m.put("riskPerShare", r2(risk));
        m.put("riskPct", r2(risk / entry * 100));
        m.put("rr", risk > 0 ? r2((target - entry) / risk) : null);
        m.put("exit", exit);
        if (riskDollars != null && riskDollars > 0 && risk > 0) {
            int byRisk = (int) Math.floor(riskDollars / risk);
            int shares = byRisk;
            if (!Double.isNaN(account) && account > 0) shares = Math.min(byRisk, (int) Math.floor(account / entry));
            m.put("shares", shares);
            m.put("positionValue", r2(shares * entry));
            m.put("dollarsAtRisk", r2(shares * risk));
            m.put("dollarsToTarget", r2(shares * (target - entry)));
            if (shares < byRisk) m.put("sizeNote", String.format("capped at %d shares by the $%,.0f account (the full risk would need %d)", shares, account, byRisk));
        }
        return m;
    }

    private static List<String> context(StockRow r) {
        List<String> c = new ArrayList<>();
        if (r == null) return List.of("Not on the screen: add it to the watchlist for its full readings");
        c.add("Zoya: " + r.zoya);
        c.add("Trend: " + r.trendClass + (r.trendClassWhy == null ? "" : " (" + r.trendClassWhy + ")"));
        c.add(String.format("Bias %s (15m %d, 1h %d, 4h %d), %s the 1h 20 EMA", r.bias, r.trend15m, r.trend1h, r.trend4h, r.above1hEma ? "above" : "below"));
        if (r.sessionPhase != null) c.add("Session: " + r.sessionName + " " + r.sessionPct + "% " + r.sessionPhase);
        if (r.rangeUsedPct != null) c.add(String.format("Range used %.0f%% of ATR, relative volume %s", r.rangeUsedPct, r.relVol == null ? "—" : String.format("%.2f×", r.relVol)));
        if (r.earningsSoon) c.add("Earnings inside 3 trading days: avoid holding through them");
        if (r.gex != null) c.add(String.format("Options: call wall %.2f, put wall %.2f, King %.2f", r.gex.callWall(), r.gex.putWall(), r.gex.king()));
        return c;
    }

    private static TradePlan.Settings copy(TradePlan.Settings s, int hold) {
        TradePlan.Settings c = new TradePlan.Settings();
        c.minRR = s.minRR;
        c.maxStopPct = s.maxStopPct;
        c.minWinRate = s.minWinRate;
        c.minTrades = s.minTrades;
        c.costBps = s.costBps;
        c.holdDays = hold;
        c.useMagnet = s.useMagnet;
        return c;
    }

    private static String signalLabel(String s) {
        return s == null ? "none" : switch (s) {
            case "BUY_NOW" -> "Buy now";
            case "READY" -> "Ready to buy";
            case "READY_SELL" -> "Ready to sell";
            case "SELL_NOW" -> "Sell now";
            case "HOLD" -> "Hold";
            default -> "Wait";
        };
    }

    private static String verdictText(String v) {
        return switch (v) {
            case "weak" -> "its record on this stock is only weak";
            case "too few trades" -> "it has too few trades on this stock to trust";
            default -> "it has no edge on this stock";
        };
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    private static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
