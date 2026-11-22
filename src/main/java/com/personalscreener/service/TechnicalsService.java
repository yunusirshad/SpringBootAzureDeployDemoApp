package com.personalscreener.service;

import com.personalscreener.analysis.Indicators;
import com.personalscreener.data.Bar;
import com.personalscreener.data.YahooClient;
import com.personalscreener.model.Num;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Indicator readings on four timeframes plus the daily closes for a chart. */
@Service
public class TechnicalsService {

    private final YahooClient yahoo;

    public TechnicalsService(YahooClient yahoo) { this.yahoo = yahoo; }

    public Map<String, Object> technicals(String symbol) throws Exception {
        long now = Instant.now().getEpochSecond();
        List<Bar> daily = yahoo.bars(symbol, "1d", "2y");
        List<Bar> h1 = StockAnalyzer.completed(yahoo.bars(symbol, "60m", "3mo"), 3600, now);
        List<Bar> m15 = StockAnalyzer.completed(yahoo.bars(symbol, "15m", "10d"), 900, now);
        List<Bar> h4 = Indicators.aggregateByDay(h1, 4);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        List<Map<String, Object>> frames = new ArrayList<>();
        frames.add(frame("Daily", daily));
        frames.add(frame("4 hour", h4));
        frames.add(frame("1 hour", h1));
        frames.add(frame("15 min", m15));
        out.put("frames", frames);

        double[] c = Indicators.closes(daily);
        double[] s50 = rolling(c, 50), s200 = rolling(c, 200);
        List<Map<String, Object>> chart = new ArrayList<>();
        for (int i = Math.max(0, daily.size() - 180); i < daily.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", daily.get(i).time());
            p.put("c", Num.round(c[i], 4));
            p.put("s50", Num.round(s50[i], 4));
            p.put("s200", Num.round(s200[i], 4));
            chart.add(p);
        }
        out.put("chart", chart);
        return out;
    }

    private static Map<String, Object> frame(String name, List<Bar> b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timeframe", name);
        m.put("bars", b.size());
        if (b.size() < 20) return m;
        double[] c = Indicators.closes(b);
        double last = c[c.length - 1];
        double ema20 = Indicators.ema(c, 20);
        m.put("close", Num.round(last, 4));
        m.put("ema20", Num.round(ema20, 4));
        m.put("aboveEma20", last > ema20);
        m.put("sma50", Num.round(Indicators.sma(c, 50), 4));
        m.put("sma200", Num.round(Indicators.sma(c, 200), 4));
        m.put("rsi", Num.round(Indicators.rsi(c, 14), 1));
        Indicators.Macd macd = Indicators.macd(c);
        m.put("macd", Num.round(macd.line(), 4));
        m.put("macdSignal", Num.round(macd.signal(), 4));
        m.put("macdUp", macd.up());
        Indicators.Adx adx = Indicators.adx(b, 14);
        m.put("adx", Num.round(adx.adx(), 1));
        m.put("plusDi", Num.round(adx.plusDi(), 1));
        m.put("minusDi", Num.round(adx.minusDi(), 1));
        m.put("stoch", Num.round(Indicators.stochastic(b, 14, 3), 1));
        m.put("bandPct", Num.round(Indicators.bollingerPosition(c, 20, 2), 1));
        m.put("atr", Num.round(Indicators.atr(b, 14), 4));
        m.put("trend", StockAnalyzer.trend(b));
        return m;
    }

    private static double[] rolling(double[] c, int n) {
        double[] o = new double[c.length];
        double s = 0;
        for (int i = 0; i < c.length; i++) {
            s += c[i];
            if (i >= n) s -= c[i - n];
            o[i] = i >= n - 1 ? s / n : Double.NaN;
        }
        return o;
    }
}
