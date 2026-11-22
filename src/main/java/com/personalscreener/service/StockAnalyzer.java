package com.personalscreener.service;

import com.personalscreener.analysis.Indicators;
import com.personalscreener.analysis.QualityScorer;
import com.personalscreener.analysis.StateClassifier;
import com.personalscreener.data.Bar;
import com.personalscreener.data.Candidate;
import com.personalscreener.data.Quote;
import com.personalscreener.data.YahooClient;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.Num;
import com.personalscreener.model.StockRow;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** Turns a screened name into a full row: daily trend, indicators, intraday trend, quality and state. */
@Component
public class StockAnalyzer {

    private final YahooClient yahoo;
    private final QualityScorer quality;
    private final MarketClock clock;

    public StockAnalyzer(YahooClient yahoo, QualityScorer quality, MarketClock clock) {
        this.yahoo = yahoo;
        this.quality = quality;
        this.clock = clock;
    }

    public StockRow analyze(Candidate c, StockRow row) throws Exception {
        row.symbol = c.symbol();
        if (c.exchange() != null && !c.exchange().isBlank()) row.exchange = c.exchange();
        if (c.name() != null && !c.name().isBlank()) row.name = c.name();
        row.floatShares = Num.of(c.floatShares());
        row.earningsDate = c.earningsDate();

        ZonedDateTime now = clock.now();
        LocalDate today = now.toLocalDate();
        boolean todayLive = clock.isTradingDay(today) && now.getHour() >= 4;

        // ---- daily: completed candles only -------------------------------------------------
        List<Bar> daily = yahoo.bars(c.symbol(), "1d", "1y");
        List<Bar> done = new ArrayList<>();
        for (Bar b : daily) if (!(todayLive && date(b).equals(today))) done.add(b);
        if (done.size() < 30) throw new IllegalStateException("Not enough daily history");
        double[] cl = Indicators.closes(done);

        row.sma50 = Num.of(Indicators.sma(cl, 50));
        row.sma200 = Num.of(Indicators.sma(cl, 200));
        if (row.sma50 != null && row.sma200 != null) {
            row.smaGapPct = Num.round((row.sma50 / row.sma200 - 1) * 100, 2);
            row.uptrend = row.sma50 > row.sma200;
        } else {
            row.smaGapPct = null;
            row.uptrend = false;
        }
        row.rsi = Num.round(Indicators.rsi(cl, 14), 1);
        Indicators.Macd m = Indicators.macd(cl);
        row.macd = Num.round(m.line(), 4);
        row.macdSignal = Num.round(m.signal(), 4);
        row.macdUp = m.up();
        Indicators.Adx a = Indicators.adx(done, 14);
        row.adx = Num.round(a.adx(), 1);
        row.plusDi = Num.round(a.plusDi(), 1);
        row.minusDi = Num.round(a.minusDi(), 1);
        row.adxRising = a.rising();
        row.side = a.rising() ? "Long" : "Short";
        row.stoch = Num.round(Indicators.stochastic(done, 14, 3), 1);
        row.bandPct = Num.round(Indicators.bollingerPosition(cl, 20, 2), 1);
        row.atr = Num.round(Indicators.atr(done, 14), 4);

        Bar priorDay = done.get(done.size() - 1);
        int n10 = Math.min(10, done.size()), n30 = Math.min(30, done.size());
        row.avgVolume10 = avgVolume(done, n10);
        row.avgVolume30 = avgVolume(done, n30);
        row.breakoutLevel = priorDay.high();
        row.breakoutSource = "prior high";

        // ---- intraday: 1h, 4h (from 1h), 15m ----------------------------------------------
        long nowSec = Instant.now().getEpochSecond();
        List<Bar> h1 = completed(yahoo.bars(c.symbol(), "60m", "3mo"), 3600, nowSec);
        List<Bar> m15 = completed(yahoo.bars(c.symbol(), "15m", "10d"), 900, nowSec);
        List<Bar> h4 = Indicators.aggregateByDay(h1, 4);
        if (!h4.isEmpty() && todayLive && clock.isRegularSession(now) && date(h4.get(h4.size() - 1)).equals(today)) {
            h4 = h4.subList(0, h4.size() - 1); // the current 4h block is still forming
        }
        double[] h1c = Indicators.closes(h1);
        row.ema20h1 = Num.round(Indicators.ema(h1c, 20), 4);
        row.above1hEma = h1c.length > 0 && row.ema20h1 != null && h1c[h1c.length - 1] > row.ema20h1;
        row.trend15m = trend(m15);
        row.trend1h = trend(h1);
        row.trend4h = trend(h4);
        int sum = row.trend15m + row.trend1h + row.trend4h;
        row.bias = sum > 0 ? "Bullish" : sum < 0 ? "Bearish" : "Mixed";
        row.trendNote = "Trend: " + (m15.size() + h1.size() + h4.size()) + " completed candles"
                + (h1.isEmpty() ? "" : " · last 1h " + hhmm(h1.get(h1.size() - 1).time() + 3600));

        // ---- live quote -----------------------------------------------------------------
        Quote q = yahoo.chartQuote(c.symbol());
        if (!Double.isNaN(q.preMarketHigh()) && todayLive && q.preMarketHigh() > row.breakoutLevel) {
            row.breakoutLevel = q.preMarketHigh();
            row.breakoutSource = "premarket high";
        }
        applyQuote(row, q);

        // ---- earnings ---------------------------------------------------------------------
        if (row.earningsDate == null) row.earningsDate = yahoo.nextEarnings(c.symbol());
        if (row.earningsDate != null) {
            int n = clock.tradingDaysUntil(today, row.earningsDate);
            row.earningsInDays = n < 0 ? null : n;
            row.earningsSoon = n >= 0 && n <= 3;
        } else {
            row.earningsInDays = null;
            row.earningsSoon = false;
        }
        row.updatedAt = now.toOffsetDateTime().toString();
        row.error = null;
        return row;
    }

    /** Price-driven fields; cheap enough to run on every 10-second tick. */
    public void applyQuote(StockRow row, Quote q) {
        if (Double.isNaN(q.price())) return;
        row.price = Num.round(q.price(), 4);
        if (!Double.isNaN(q.previousClose())) row.previousClose = q.previousClose();
        row.changePct = Num.round(q.changePct(), 2);
        if (!Double.isNaN(q.dayHigh())) row.dayHigh = q.dayHigh();
        if (!Double.isNaN(q.dayLow())) row.dayLow = q.dayLow();
        if (!Double.isNaN(q.volume())) row.volume = q.volume();
        if (!Double.isNaN(q.spreadBps())) row.spreadBps = Num.round(q.spreadBps(), 1);
        else if (!q.hasBidAsk() && !Double.isNaN(q.bid())) row.spreadBps = null;

        double atr = Num.d(row.atr), price = q.price();
        row.atrPct = atr > 0 && price > 0 ? Num.round(atr / price * 100, 2) : null;
        if (row.dayHigh != null && row.dayLow != null) {
            double range = row.dayHigh - row.dayLow;
            row.dayRange = Num.round(range, 4);
            row.rangeUsedPct = atr > 0 ? Num.round(range / atr * 100, 1) : null;
            row.rangeLeft = atr > 0 ? Num.round(atr - range, 4) : null;
        }
        if (row.volume != null) {
            row.relVol = row.avgVolume10 != null && row.avgVolume10 > 0 ? Num.round(row.volume / row.avgVolume10, 2) : null;
            row.relVol30 = row.avgVolume30 != null && row.avgVolume30 > 0 ? Num.round(row.volume / row.avgVolume30, 2) : null;
        }
        row.dollarVolume = row.avgVolume10 != null ? Num.round(row.avgVolume10 * price, 0) : null;
        if (row.breakoutLevel != null && price > 0) {
            row.toBreakoutPct = Num.round((row.breakoutLevel / price - 1) * 100, 2);
        }
        double closeLoc = row.dayHigh != null && row.dayLow != null && row.dayHigh > row.dayLow
                ? (price - row.dayLow) / (row.dayHigh - row.dayLow) : Double.NaN;
        row.state = StateClassifier.classify(Num.d(row.changePct), Num.d(row.atrPct), Num.d(row.relVol), closeLoc);

        QualityScorer.Result r = quality.score(Num.d(row.relVol), Num.d(row.dollarVolume), Num.d(row.spreadBps),
                Num.d(row.floatShares), Num.d(row.atrPct));
        row.qualityScore = r.score();
        row.qualityPass = r.pass();
        row.qualityReasons = r.reasons();
    }

    static int trend(List<Bar> bars) {
        if (bars.size() < 24) return 0;
        double[] c = Indicators.closes(bars);
        double[] e = Indicators.emaSeries(c, 20);
        double last = c[c.length - 1], ema = e[e.length - 1], prev = e[e.length - 4];
        if (last > ema && ema >= prev) return 1;
        if (last < ema && ema <= prev) return -1;
        return 0;
    }

    static List<Bar> completed(List<Bar> bars, long seconds, long nowSec) {
        if (!bars.isEmpty() && bars.get(bars.size() - 1).time() + seconds > nowSec) {
            return bars.subList(0, bars.size() - 1);
        }
        return bars;
    }

    private static Double avgVolume(List<Bar> b, int n) {
        if (n == 0) return null;
        double s = 0;
        for (int i = b.size() - n; i < b.size(); i++) s += b.get(i).volume();
        return s / n;
    }

    static LocalDate date(Bar b) { return Instant.ofEpochSecond(b.time()).atZone(MarketClock.ET).toLocalDate(); }

    private static String hhmm(long epoch) {
        var t = Instant.ofEpochSecond(epoch).atZone(MarketClock.ET).toLocalTime();
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return String.format("%d:%02d %s", h, t.getMinute(), t.getHour() < 12 ? "AM" : "PM");
    }
}
