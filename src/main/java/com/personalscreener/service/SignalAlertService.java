package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.analysis.SetupScore;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.StockRow;
import com.personalscreener.store.JsonStore;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BUY NOW and SELL NOW alerts for the Core list. When a Core name's signal is Buy now or Sell now during market hours
 * (9:30 AM to 4:00 PM ET on trading days), it alerts once per name, per signal, per day: in the page (and a browser
 * notification) and on the phone channels (Telegram / ntfy) when they are set up.
 */
@Service
public class SignalAlertService {

    private static final String FILE = "signal-alerts.json";

    public static class SignalAlert {
        public String id, symbol, name, signal, at, why;
        public Double price, stop, target, rewardRisk;
        public boolean superStock;
        public List<String> sent = List.of();
    }

    private final JsonStore store;
    private final MarketClock clock;
    private final Notifier notifier;
    private final List<SignalAlert> alerts;

    public SignalAlertService(JsonStore store, MarketClock clock, Notifier notifier) {
        this.store = store;
        this.clock = clock;
        this.notifier = notifier;
        this.alerts = new CopyOnWriteArrayList<>(store.read(FILE, new TypeReference<List<SignalAlert>>() {}, new ArrayList<>()));
    }

    /** Checks the ranked Core list; returns the alerts that fired now. */
    public synchronized List<SignalAlert> check(List<StockRow> core) {
        ZonedDateTime now = clock.now();
        LocalDate d = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        if (!clock.isTradingDay(d) || t.isBefore(LocalTime.of(9, 30)) || !t.isBefore(LocalTime.of(16, 0))) return List.of();
        List<SignalAlert> fired = new ArrayList<>();
        for (StockRow r : core) {
            if (!SetupScore.BUY_NOW.equals(r.signal) && !SetupScore.SELL_NOW.equals(r.signal)) continue;
            String id = r.symbol + "|" + r.signal + "|" + d;
            if (alerts.stream().anyMatch(a -> a.id.equals(id))) continue;
            SignalAlert a = new SignalAlert();
            a.id = id;
            a.symbol = r.symbol;
            a.name = r.name;
            a.signal = r.signal;
            a.at = OffsetDateTime.now().toString();
            a.price = r.price;
            a.superStock = r.superStock;
            if (SetupScore.BUY_NOW.equals(r.signal)) {
                a.stop = r.setupStop;
                a.target = r.setupTarget;
                a.rewardRisk = r.rewardRisk;
                a.why = (r.superStock ? "Super stock. " : "") + "Through the breakout, above the 1h 20 EMA, bull session, R:R "
                        + (r.rewardRisk == null ? "n/a" : String.format("%.1f", r.rewardRisk));
            } else {
                a.why = String.join(", ", r.signalNotes);
            }
            a.sent = notifier.send(title(a), body(a));
            alerts.add(a);
            fired.add(a);
        }
        if (!fired.isEmpty()) {
            while (alerts.size() > 500) alerts.remove(0);
            store.write(FILE, new ArrayList<>(alerts));
        }
        return fired;
    }

    public static String title(SignalAlert a) {
        return (SetupScore.BUY_NOW.equals(a.signal) ? "BUY NOW " : "SELL NOW ") + a.symbol
                + (a.price == null ? "" : String.format(" at $%.2f", a.price)) + (a.superStock ? " ★" : "");
    }

    public static String body(SignalAlert a) {
        StringBuilder b = new StringBuilder();
        if (a.name != null) b.append(a.name).append('\n');
        if (a.stop != null && a.target != null) b.append(String.format("Stop $%.2f · target $%.2f%n", a.stop, a.target));
        if (a.why != null && !a.why.isBlank()) b.append(a.why);
        return b.toString().trim();
    }

    /** Newest first. */
    public List<SignalAlert> all() {
        List<SignalAlert> l = new ArrayList<>(alerts);
        Collections.reverse(l);
        return l;
    }

    /** From the last 24 hours, for the page's notifications. */
    public List<SignalAlert> recent() {
        Instant since = Instant.now().minus(Duration.ofHours(24));
        return alerts.stream().filter(a -> OffsetDateTime.parse(a.at).toInstant().isAfter(since)).toList();
    }
}
