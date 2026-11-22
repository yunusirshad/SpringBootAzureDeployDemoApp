package com.personalscreener.service;

import com.personalscreener.analysis.QuantumShift;
import com.personalscreener.data.Bar;
import com.personalscreener.data.YahooClient;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.Num;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/**
 * Backtests Quantum Shift signals bar by bar.
 *
 * <p>Rules: a signal on a bar's close enters at the next bar's open. The initial stop is {@code stopAtr} ATRs away;
 * it trails as a chandelier ({@code trailAtr} ATRs off the best close since entry) and never loosens. A position
 * exits at the next open after a close through the stop, after an opposite shift, or after {@code maxBars} bars.
 * Costs of {@code costBps} are charged on each side. Only signals inside the test window count; the bars before it
 * warm the indicator up. A position still open at the end is closed at the last close and marked open.
 */
@Service
public class BacktestService {

    private final YahooClient yahoo;
    private final ScreenService screen;
    private final WatchlistService watchlist;

    public BacktestService(YahooClient yahoo, ScreenService screen, WatchlistService watchlist) {
        this.yahoo = yahoo;
        this.screen = screen;
        this.watchlist = watchlist;
    }

    public record Params(int days, String interval, double threshold, boolean shorts, double stopAtr, double trailAtr,
                         int maxBars, double costBps) {
        public static Params defaults() { return new Params(60, "60m", 60, true, 2.0, 2.5, 35, 5); }
    }

    public record Trade(String symbol, String side, String entryTime, double entry, String exitTime, double exit,
                        double returnPct, double r, int bars, String exitReason, double energy) {}

    public record StockResult(String symbol, int trades, int wins, Double winRate, Double avgReturnPct, Double totalReturnPct,
                              Double profitFactor, Double maxDrawdownPct, Double avgR, Double buyHoldPct,
                              Double longReturnPct, Double shortReturnPct, String lastSignal, Double lastEnergy,
                              int regime, String error, List<Trade> tradeList) {}

    /** Tickers in the app: every screened row plus the watchlist. */
    public List<String> universe() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        screen.snapshot().rows.forEach(r -> s.add(r.symbol));
        s.addAll(watchlist.all());
        return new ArrayList<>(s);
    }

    public Map<String, Object> run(List<String> tickers, Params p) throws InterruptedException {
        List<String> list = tickers == null || tickers.isEmpty() ? universe() : tickers;
        if (list.size() > 80) list = list.subList(0, 80);
        ExecutorService ex = Executors.newFixedThreadPool(6);
        List<Future<StockResult>> fs = new ArrayList<>();
        for (String t : list) fs.add(ex.submit(() -> one(t, p)));
        List<StockResult> results = new ArrayList<>();
        for (Future<StockResult> f : fs) {
            try { results.add(f.get(60, TimeUnit.SECONDS)); } catch (ExecutionException | TimeoutException e) { /* skipped below */ }
        }
        ex.shutdown();

        List<Trade> all = new ArrayList<>();
        results.forEach(r -> all.addAll(r.tradeList()));
        all.sort(Comparator.comparing(Trade::entryTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("params", p);
        out.put("tickers", list.size());
        out.put("tested", (int) results.stream().filter(r -> r.error() == null).count());
        out.put("summary", summary(all, results));
        out.put("longs", summary(all.stream().filter(t -> t.side().equals("Long")).toList(), List.of()));
        out.put("shorts", summary(all.stream().filter(t -> t.side().equals("Short")).toList(), List.of()));
        results.sort(Comparator.comparing((StockResult r) -> r.totalReturnPct() == null ? -1e9 : r.totalReturnPct()).reversed());
        out.put("stocks", results);
        out.put("trades", all);
        out.put("generatedAt", java.time.OffsetDateTime.now().toString());
        return out;
    }

    StockResult one(String symbol, Params p) {
        try {
            String range = p.interval().equals("1d") ? "2y" : p.interval().equals("15m") ? "60d" : "1y";
            List<Bar> raw = yahoo.bars(symbol, p.interval(), range);
            long secs = switch (p.interval()) { case "15m" -> 900; case "1d" -> 86_400; default -> 3600; };
            List<Bar> bars = StockAnalyzer.completed(raw, secs, Instant.now().getEpochSecond());
            return simulate(symbol, bars, p);
        } catch (Exception e) {
            return new StockResult(symbol, 0, 0, null, null, null, null, null, null, null, null, null, null, null, 0,
                    e.getMessage(), List.of());
        }
    }

    static StockResult simulate(String symbol, List<Bar> bars, Params p) {
        if (bars.size() < 120) throw new IllegalStateException("Only " + bars.size() + " bars");
        QuantumShift.Series s = QuantumShift.compute(bars, p.threshold());
        LocalDate startDay = Instant.ofEpochSecond(bars.get(bars.size() - 1).time()).atZone(MarketClock.ET).toLocalDate().minusDays(p.days());
        long start = startDay.atStartOfDay(MarketClock.ET).toEpochSecond();
        int first = 0;
        while (first < bars.size() && bars.get(first).time() < start) first++;
        double cost = p.costBps() / 10_000;

        List<Trade> trades = new ArrayList<>();
        int side = 0, entryIdx = -1;
        double entry = 0, stop = 0, best = 0, risk = 0, entryEnergy = 0;
        for (int i = Math.max(first, 1); i < bars.size(); i++) {
            Bar b = bars.get(i);
            // 1. act on yesterday's decision at today's open
            int sig = s.signal()[i - 1];
            if (side != 0) {
                Bar prev = bars.get(i - 1);
                boolean stopped = side == 1 ? prev.close() < stop : prev.close() > stop;
                boolean flip = sig == -side;
                boolean timeUp = i - entryIdx >= p.maxBars();
                if (stopped || flip || timeUp) {
                    trades.add(close(symbol, side, bars, entryIdx, entry, i, b.open(), risk, cost,
                            stopped ? "stop" : flip ? "opposite shift" : "time", entryEnergy));
                    side = 0;
                }
            }
            if (side == 0 && sig != 0 && (sig == 1 || p.shorts()) && i - 1 >= first) {
                side = sig;
                entryIdx = i;
                entry = b.open();
                double atr = s.atr()[i - 1];
                risk = p.stopAtr() * atr;
                stop = side == 1 ? entry - risk : entry + risk;
                best = entry;
                entryEnergy = s.energy()[i - 1];
            }
            // 2. trail on this bar's close
            if (side != 0) {
                double atr = s.atr()[i];
                if (side == 1) { best = Math.max(best, b.close()); stop = Math.max(stop, best - p.trailAtr() * atr); }
                else { best = Math.min(best, b.close()); stop = Math.min(stop, best + p.trailAtr() * atr); }
            }
        }
        if (side != 0) {
            int last = bars.size() - 1;
            trades.add(close(symbol, side, bars, entryIdx, entry, last, bars.get(last).close(), risk, cost, "open", entryEnergy));
        }

        // stats
        int wins = 0;
        double eq = 1, peak = 1, maxDd = 0, gain = 0, loss = 0, sumR = 0, sumRet = 0, longEq = 1, shortEq = 1;
        for (Trade t : trades) {
            double r = t.returnPct() / 100;
            if (r > 0) { wins++; gain += r; } else loss -= r;
            eq *= 1 + r;
            if (t.side().equals("Long")) longEq *= 1 + r; else shortEq *= 1 + r;
            peak = Math.max(peak, eq);
            maxDd = Math.max(maxDd, 1 - eq / peak);
            sumR += t.r();
            sumRet += t.returnPct();
        }
        int n = trades.size();
        double bh = first < bars.size() ? (bars.get(bars.size() - 1).close() / bars.get(first).open() - 1) * 100 : Double.NaN;
        int lastSig = -1;
        for (int i = bars.size() - 1; i >= first; i--) if (s.signal()[i] != 0) { lastSig = i; break; }
        String lastSignal = lastSig < 0 ? null
                : (s.signal()[lastSig] > 0 ? "Shift up " : "Shift down ") + et(bars.get(lastSig).time());
        return new StockResult(symbol, n, wins, n == 0 ? null : Num.round(100.0 * wins / n, 1),
                n == 0 ? null : Num.round(sumRet / n, 2), Num.round((eq - 1) * 100, 2),
                loss == 0 ? null : Num.round(gain / loss, 2), Num.round(maxDd * 100, 2),
                n == 0 ? null : Num.round(sumR / n, 2), Num.round(bh, 2),
                Num.round((longEq - 1) * 100, 2), Num.round((shortEq - 1) * 100, 2), lastSignal,
                Num.round(s.energy()[bars.size() - 1], 0), s.regime()[bars.size() - 1], null, trades);
    }

    private static Trade close(String symbol, int side, List<Bar> bars, int entryIdx, double entry, int exitIdx,
                               double exit, double risk, double cost, String reason, double energy) {
        double gross = side == 1 ? exit / entry - 1 : entry / exit - 1;
        double net = gross - 2 * cost;
        double r = risk > 0 ? (side == 1 ? exit - entry : entry - exit) / risk : 0;
        return new Trade(symbol, side == 1 ? "Long" : "Short", et(bars.get(entryIdx).time()), Num.round(entry, 4),
                et(bars.get(exitIdx).time()), Num.round(exit, 4), Num.round(net * 100, 2), Num.round(r, 2),
                exitIdx - entryIdx, reason, Num.round(energy, 0));
    }

    private static Map<String, Object> summary(List<Trade> trades, List<StockResult> stocks) {
        Map<String, Object> m = new LinkedHashMap<>();
        int n = trades.size(), wins = 0;
        double gain = 0, loss = 0, sum = 0, sumR = 0, bars = 0;
        double best = Double.NaN, worst = Double.NaN;
        for (Trade t : trades) {
            double r = t.returnPct();
            if (r > 0) { wins++; gain += r; } else loss -= r;
            sum += r;
            sumR += t.r();
            bars += t.bars();
            best = Double.isNaN(best) ? r : Math.max(best, r);
            worst = Double.isNaN(worst) ? r : Math.min(worst, r);
        }
        m.put("trades", n);
        m.put("wins", wins);
        m.put("winRate", n == 0 ? null : Num.round(100.0 * wins / n, 1));
        m.put("avgReturnPct", n == 0 ? null : Num.round(sum / n, 2));
        m.put("avgR", n == 0 ? null : Num.round(sumR / n, 2));
        m.put("profitFactor", loss == 0 ? null : Num.round(gain / loss, 2));
        m.put("expectancyPct", n == 0 ? null : Num.round(sum / n, 2));
        m.put("avgBarsHeld", n == 0 ? null : Num.round(bars / n, 1));
        m.put("bestPct", Num.of(best));
        m.put("worstPct", Num.of(worst));
        if (!stocks.isEmpty()) {
            List<StockResult> ok = stocks.stream().filter(r -> r.error() == null).toList();
            m.put("equalWeightReturnPct", Num.round(ok.stream().mapToDouble(r -> Num.d(r.totalReturnPct())).filter(d -> !Double.isNaN(d)).average().orElse(Double.NaN), 2));
            m.put("buyHoldReturnPct", Num.round(ok.stream().mapToDouble(r -> Num.d(r.buyHoldPct())).filter(d -> !Double.isNaN(d)).average().orElse(Double.NaN), 2));
            m.put("stocksPositive", ok.stream().filter(r -> Num.d(r.totalReturnPct()) > 0).count());
            m.put("stocksWithTrades", ok.stream().filter(r -> r.trades() > 0).count());
        }
        return m;
    }

    private static String et(long epoch) {
        var t = Instant.ofEpochSecond(epoch).atZone(MarketClock.ET);
        return String.format("%s %02d:%02d", t.toLocalDate(), t.getHour(), t.getMinute());
    }
}
