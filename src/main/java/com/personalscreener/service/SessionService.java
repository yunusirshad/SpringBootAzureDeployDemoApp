package com.personalscreener.service;

import com.personalscreener.analysis.Indicators;
import com.personalscreener.analysis.SessionPhases;
import com.personalscreener.data.Bar;
import com.personalscreener.data.YahooClient;
import com.personalscreener.model.Num;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Session phases for the chart. The sessions always come from 5-minute candles; the timeframe only changes the
 * candles drawn underneath, so the boxes and percentages are identical on every timeframe.
 */
@Service
public class SessionService {

    public static final List<String> TIMEFRAMES = List.of("5m", "15m", "1h", "4h", "1d");
    private final YahooClient yahoo;

    public SessionService(YahooClient yahoo) { this.yahoo = yahoo; }

    public Map<String, Object> read(String symbol, String tf, int days) throws Exception {
        if (!TIMEFRAMES.contains(tf)) throw new IllegalArgumentException("Timeframe must be one of " + TIMEFRAMES);
        long now = Instant.now().getEpochSecond();
        YahooClient.Chart base = yahoo.chart(symbol, "5m", "1mo", true);
        String type = base.meta().path("instrumentType").asText("");
        boolean stock = type.equals("EQUITY") || type.equals("ETF");
        List<SessionPhases.Session> all = SessionPhases.read(base.bars(), now, stock);

        // keep the sessions on the last `days` trade dates
        TreeSet<LocalDate> dates = new TreeSet<>();
        all.forEach(s -> dates.add(s.tradeDate()));
        LocalDate from = dates.isEmpty() ? LocalDate.MIN : dates.descendingSet().stream().limit(days).min(Comparator.naturalOrder()).orElse(LocalDate.MIN);
        List<SessionPhases.Session> shown = all.stream().filter(s -> !s.tradeDate().isBefore(from)).toList();
        long windowStart = shown.isEmpty() ? now - days * 86_400L : shown.get(0).start();

        List<Bar> candles;
        long secs;
        switch (tf) {
            case "5m" -> { candles = base.bars(); secs = 300; }
            case "15m" -> { candles = yahoo.chart(symbol, "15m", "1mo", true).bars(); secs = 900; }
            case "1h" -> { candles = yahoo.chart(symbol, "60m", "1mo", true).bars(); secs = 3600; }
            case "4h" -> { candles = fourHour(yahoo.chart(symbol, "60m", "1mo", true).bars()); secs = 14_400; }
            default -> { candles = yahoo.chart(symbol, "1d", "3mo", false).bars(); secs = 86_400; }
        }
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Bar b : candles) {
            if (b.time() + secs <= windowStart) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", b.time());
            m.put("o", Num.round(b.open(), 5));
            m.put("h", Num.round(b.high(), 5));
            m.put("l", Num.round(b.low(), 5));
            m.put("c", Num.round(b.close(), 5));
            cs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("name", Optional.ofNullable(base.meta().path("longName").asText(null)).orElse(base.meta().path("shortName").asText(symbol)));
        out.put("instrumentType", type);
        out.put("timeframe", tf);
        out.put("candleSeconds", secs);
        out.put("baseTimeframe", "5m");
        out.put("candles", cs);
        out.put("sessions", shown);
        // the magnet zone from the same month of 5-minute candles (regular hours only for stocks and ETFs)
        List<Bar> profileBars = stock ? base.bars().stream().filter(SessionService::regularHours).toList() : base.bars();
        out.put("magnet", com.personalscreener.analysis.MagnetZones.build(profileBars));
        try {
            var profiles = com.personalscreener.analysis.MagnetZones.profiles(profileBars, yahoo.bars(symbol, "1d", "2y"),
                    java.time.LocalDate.now(com.personalscreener.market.MarketClock.ET).plusDays(1));
            out.put("magnetZones", com.personalscreener.analysis.MagnetZones.confluence(profiles));
        } catch (Exception e) {
            out.put("magnetZones", List.of());
        }
        out.put("generatedAt", java.time.OffsetDateTime.now().toString());
        return out;
    }

    /** 9:30 AM to 4:00 PM ET. */
    static boolean regularHours(Bar b) {
        var t = java.time.Instant.ofEpochSecond(b.time()).atZone(com.personalscreener.market.MarketClock.ET).toLocalTime();
        return !t.isBefore(java.time.LocalTime.of(9, 30)) && t.isBefore(java.time.LocalTime.of(16, 0));
    }

    /**
     * The magnet zone over the last {@code days} trading days plus its walk-forward touch test over 60 days of
     * 5-minute candles.
     */
    public Map<String, Object> magnet(String symbol, int days) throws Exception {
        YahooClient.Chart c = yahoo.chart(symbol, "5m", "60d", false);
        String type = c.meta().path("instrumentType").asText("");
        boolean stock = type.equals("EQUITY") || type.equals("ETF");
        List<Bar> bars = stock ? c.bars().stream().filter(SessionService::regularHours).toList() : c.bars();
        TreeSet<java.time.LocalDate> dates = new TreeSet<>();
        bars.forEach(b -> dates.add(com.personalscreener.analysis.PvConfirm.date(b)));
        java.time.LocalDate from = dates.descendingSet().stream().limit(days).min(Comparator.naturalOrder()).orElse(java.time.LocalDate.MIN);
        List<Bar> recent = bars.stream().filter(b -> !com.personalscreener.analysis.PvConfirm.date(b).isBefore(from)).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);
        out.put("profile", com.personalscreener.analysis.MagnetZones.build(recent));
        out.put("stats", com.personalscreener.analysis.MagnetZones.touchStats(bars, days));
        out.put("price", bars.isEmpty() ? null : bars.get(bars.size() - 1).close());
        // all timeframes combined: the zones where several profiles agree, and their own pull test
        List<Bar> daily = yahoo.bars(symbol, "1d", "2y");
        java.time.LocalDate tomorrow = java.time.LocalDate.now(com.personalscreener.market.MarketClock.ET).plusDays(1);
        var profiles = com.personalscreener.analysis.MagnetZones.profiles(bars, daily, tomorrow);
        out.put("profiles", profiles);
        out.put("zones", com.personalscreener.analysis.MagnetZones.confluence(profiles));
        out.put("zoneStats", com.personalscreener.analysis.MagnetZones.confluenceStats(bars, daily, 20));
        return out;
    }

    /** 4-hour candles on fixed New York clock blocks (00, 04, 08, 12, 16, 20), so every market lines up. */
    static List<Bar> fourHour(List<Bar> h1) {
        LinkedHashMap<Long, List<Bar>> blocks = new LinkedHashMap<>();
        for (Bar b : h1) {
            var t = Instant.ofEpochSecond(b.time()).atZone(com.personalscreener.market.MarketClock.ET);
            long key = t.toLocalDate().atTime(t.getHour() / 4 * 4, 0).atZone(com.personalscreener.market.MarketClock.ET).toEpochSecond();
            blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        List<Bar> out = new ArrayList<>();
        blocks.forEach((k, v) -> {
            Bar m = Indicators.aggregateByDay(v, v.size()).get(0);
            out.add(new Bar(k, m.open(), m.high(), m.low(), m.close(), m.volume()));
        });
        return out;
    }
}
