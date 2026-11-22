package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.model.Alert;
import com.personalscreener.store.JsonStore;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** One-shot price alerts. Each rings once and stays in the log. */
@Service
public class AlertService {

    private static final String FILE = "alerts.json";
    private static final Set<String> TYPES = Set.of("ABOVE", "BELOW", "PCT_UP", "PCT_DOWN");

    private final JsonStore store;
    private final List<Alert> alerts;

    public AlertService(JsonStore store) {
        this.store = store;
        this.alerts = new CopyOnWriteArrayList<>(store.read(FILE, new TypeReference<List<Alert>>() {}, new ArrayList<>()));
    }

    public List<Alert> all() { return new ArrayList<>(alerts); }

    public Set<String> activeSymbols() {
        Set<String> s = new TreeSet<>();
        for (Alert a : alerts) if (a.triggeredAt == null) s.add(a.symbol);
        return s;
    }

    public Alert create(String symbol, String type, double level, String note) {
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) throw new IllegalArgumentException("Type must be one of " + TYPES);
        List<String> parsed = WatchlistService.parse(symbol);
        if (parsed.size() != 1) throw new IllegalArgumentException("One ticker, please");
        if (!(level > 0)) throw new IllegalArgumentException("Level must be above zero");
        Alert a = new Alert();
        a.id = UUID.randomUUID().toString().substring(0, 8);
        a.symbol = parsed.get(0);
        a.type = t;
        a.level = level;
        a.note = note == null ? "" : note.strip();
        a.createdAt = OffsetDateTime.now().toString();
        alerts.add(a);
        save();
        return a;
    }

    public boolean delete(String id) {
        boolean r = alerts.removeIf(a -> a.id.equals(id));
        if (r) save();
        return r;
    }

    /** Checks every live alert for this symbol. Returns the ones that rang now. */
    public List<Alert> check(String symbol, double price, double previousClose) {
        List<Alert> rang = new ArrayList<>();
        if (Double.isNaN(price)) return rang;
        double pct = previousClose > 0 ? (price / previousClose - 1) * 100 : Double.NaN;
        for (Alert a : alerts) {
            if (a.triggeredAt != null || !a.symbol.equals(symbol)) continue;
            boolean hit = switch (a.type) {
                case "ABOVE" -> price >= a.level;
                case "BELOW" -> price <= a.level;
                case "PCT_UP" -> !Double.isNaN(pct) && pct >= a.level;
                case "PCT_DOWN" -> !Double.isNaN(pct) && pct <= -a.level;
                default -> false;
            };
            if (hit) {
                a.triggeredAt = OffsetDateTime.now().toString();
                a.triggeredPrice = price;
                rang.add(a);
            }
        }
        if (!rang.isEmpty()) save();
        return rang;
    }

    private void save() { store.write(FILE, new ArrayList<>(alerts)); }
}
