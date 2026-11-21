package com.screener.data;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.screener.config.AppConfig;
import com.screener.model.Bar;
import com.screener.model.Fundamentals;
import com.screener.model.ListedSymbol;
import com.screener.model.OptionChain;
import com.screener.model.OptionContract;
import com.screener.model.Quote;

/**
 * Made-up tickers (DEMA..DEMI) so the app runs with no network and no keys. Each one is built to
 * show a different outcome of the screen. None of it is market data.
 */
public class DemoMarketData implements MarketData {

    private final Clock clock;
    private final Map<String, Fundamentals> funds = new LinkedHashMap<>();

    public DemoMarketData(Clock clock) {
        this.clock = clock;
        add("DEMA", "Demo Alpha Inc.", "NASDAQ", 8.40, 900e6, 12.00, 50e6, 200e6, 1.2e9, 0.9e9, 0.18, 0.55, 0.21, 0.15, 0.17, 40e6, 2.8);
        add("DEMB", "Demo Beta Corp.", "NYSE", 14.20, 2.1e9, 19.00, 120e6, 300e6, 1.5e9, 1.1e9, 0.09, 0.42, 0.12, 0.08, 0.10, 60e6, 1.9);
        add("DEMC", "Demo Gamma Bank", "NYSE", 11.00, 1.3e9, 15.00, 40e6, 90e6, 1.0e9, 0.8e9, 0.07, 0.60, 0.30, 0.22, 0.14, 30e6, 1.4);
        add("DEMD", "Demo Delta Ltd.", "NASDAQ", 22.50, 3.0e9, 31.00, 10e6, 400e6, 2.0e9, 1.7e9, 0.12, 0.50, 0.20, 0.15, 0.12, 90e6, 3.1);
        add("DEME", "Demo Epsilon Holdings", "NASDAQ", 6.75, 640e6, 9.50, 20e6, 60e6, 0.5e9, 0.4e9, 0.11, 0.35, 0.05, 0.02, 0.03, -5e6, 1.6);
        add("DEMF", "Demo Foxtrot Energy", "NYSE", 9.10, 780e6, 13.50, 400e6, 100e6, 1.1e9, 0.5e9, 0.06, 0.28, 0.09, 0.04, 0.06, 15e6, 1.1);
        add("DEMG", "Demo Golf Retail", "NYSE", 17.80, 1.6e9, 19.50, 30e6, 150e6, 0.9e9, 0.7e9, 0.08, 0.38, 0.10, 0.07, 0.09, 25e6, 2.0);
        add("DEMH", "Demo Hotel Biotech", "NASDAQ", 3.10, 450e6, 5.00, 0d, 80e6, 0.12e9, 0.10e9, 0.30, 0.20, -0.45, -0.60, -0.35, -40e6, 4.5);
        add("DEMI", "Demo India Micro", "NASDAQ", 2.20, 150e6, 4.00, 5e6, 20e6, 0.08e9, 0.06e9, 0.15, 0.40, 0.10, 0.05, 0.08, 2e6, 2.2);
    }

    private void add(String sym, String name, String exch, double price, double mcap, double target, double debt,
                     double cash, double assets, double equity, double revGrowth, double gross, double op,
                     double net, double roe, double fcf, double current) {
        LocalDate today = LocalDate.now(clock.withZone(AppConfig.NEW_YORK));
        LocalDate earnings = today.plusDays(7L + Math.abs(sym.hashCode() % 60));
        funds.put(sym, new Fundamentals(sym, name, exch, price, mcap, target, debt, cash, assets, equity, revGrowth,
                earnings, gross, op, net, roe, fcf, current));
    }

    @Override
    public String name() {
        return "demo";
    }

    @Override
    public List<ListedSymbol> universe() {
        return funds.values().stream().map(f -> new ListedSymbol(f.symbol(), f.name(), f.exchange())).toList();
    }

    @Override
    public Map<String, Quote> quotes(List<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (String s : symbols) {
            Fundamentals f = funds.get(s);
            if (f != null) out.put(s, new Quote(s, f.name(), f.exchange(), f.price(), f.marketCap()));
        }
        return out;
    }

    @Override
    public Fundamentals fundamentals(String symbol) {
        Fundamentals f = funds.get(symbol);
        if (f == null) throw new IllegalArgumentException("Unknown demo symbol " + symbol);
        return f;
    }

    @Override
    public List<Bar> dailyBars(String symbol, int days) {
        double last = fundamentals(symbol).price();
        Random rnd = new Random(symbol.hashCode());
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = LocalDate.now(clock.withZone(AppConfig.NEW_YORK)).minusDays(1);
        while (dates.size() < days) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(0, d);
            d = d.minusDays(1);
        }
        // Walk backwards from the last close so the series ends on the quoted price.
        double[] closes = new double[days];
        closes[days - 1] = last;
        double drift = (symbol.hashCode() % 2 == 0) ? 0.003 : -0.002;
        for (int i = days - 2; i >= 0; i--) {
            closes[i] = closes[i + 1] / (1 + drift + rnd.nextGaussian() * 0.025);
        }
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            double c = closes[i];
            double o = i == 0 ? c : closes[i - 1] * (1 + rnd.nextGaussian() * 0.005);
            double h = Math.max(o, c) * (1 + Math.abs(rnd.nextGaussian()) * 0.012);
            double l = Math.min(o, c) * (1 - Math.abs(rnd.nextGaussian()) * 0.012);
            Instant t = dates.get(i).atTime(9, 30).atZone(AppConfig.NEW_YORK).toInstant();
            bars.add(new Bar(t, r(o), r(h), r(l), r(c), 1_000_000L + rnd.nextInt(3_000_000), "DAY"));
        }
        return bars;
    }

    @Override
    public List<Bar> intradayBars(String symbol, String interval, int days, boolean prePost) {
        int minutes = Integer.parseInt(interval.replace("m", ""));
        List<Bar> daily = dailyBars(symbol, days);
        Random rnd = new Random(symbol.hashCode() * 31L);
        Instant now = clock.instant();
        List<Bar> out = new ArrayList<>();
        for (Bar day : daily) {
            LocalDate date = day.time().atZone(AppConfig.NEW_YORK).toLocalDate();
            LocalTime start = prePost ? LocalTime.of(4, 0) : LocalTime.of(9, 30);
            LocalTime end = prePost ? LocalTime.of(20, 0) : LocalTime.of(16, 0);
            double px = day.open();
            for (LocalTime t = start; t.isBefore(end); t = t.plusMinutes(minutes)) {
                Instant ts = ZonedDateTime.of(date, t, AppConfig.NEW_YORK).toInstant();
                if (ts.plusSeconds(minutes * 60L).isAfter(now)) break;
                double c = px * (1 + rnd.nextGaussian() * 0.003);
                double h = Math.max(px, c) * 1.001, l = Math.min(px, c) * 0.999;
                String session = Sessions.of(ts);
                long vol = "REGULAR".equals(session) ? 20_000 + rnd.nextInt(80_000) : 1_000 + rnd.nextInt(8_000);
                out.add(new Bar(ts, r(px), r(h), r(l), r(c), vol, session));
                px = c;
            }
        }
        return out;
    }

    @Override
    public OptionChain optionChain(String symbol, int maxExpirations) {
        double spot = fundamentals(symbol).price();
        double step = spot < 5 ? 0.5 : 1.0;
        LocalDate friday = LocalDate.now(clock.withZone(AppConfig.NEW_YORK)).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
        List<OptionContract> list = new ArrayList<>();
        for (int e = 0; e < maxExpirations; e++) {
            LocalDate exp = friday.plusWeeks(e);
            for (double k = Math.floor(spot * 0.6 / step) * step; k <= spot * 1.4; k += step) {
                long callOi = Math.round(4000 * Math.exp(-Math.pow((k - spot * 1.12) / (spot * 0.06), 2)) + 50);
                long putOi = Math.round(3500 * Math.exp(-Math.pow((k - spot * 0.90) / (spot * 0.06), 2)) + 50);
                list.add(new OptionContract(true, r(k), exp, callOi, 0.65));
                list.add(new OptionContract(false, r(k), exp, putOi, 0.70));
            }
        }
        return new OptionChain(symbol, spot, list);
    }

    private static double r(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
