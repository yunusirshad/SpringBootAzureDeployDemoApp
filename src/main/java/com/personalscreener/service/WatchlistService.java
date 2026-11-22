package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.store.JsonStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/** The names you add by hand. They are analyzed every run whether or not they pass the screen. */
@Service
public class WatchlistService {

    private static final String FILE = "watchlist.json";
    private static final Pattern TICKER = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,9}$");

    private final JsonStore store;
    private final Set<String> tickers;

    public WatchlistService(JsonStore store) {
        this.store = store;
        this.tickers = Collections.synchronizedSet(new LinkedHashSet<>(
                store.read(FILE, new TypeReference<List<String>>() {}, new ArrayList<>())));
    }

    public List<String> all() {
        synchronized (tickers) { return new ArrayList<>(tickers); }
    }

    public boolean contains(String t) { return tickers.contains(t); }

    /** Adds every valid ticker in a comma, space or newline separated string. Returns what was added. */
    public List<String> add(String raw) {
        List<String> added = new ArrayList<>();
        for (String part : parse(raw)) if (tickers.add(part)) added.add(part);
        if (!added.isEmpty()) save();
        return added;
    }

    public boolean remove(String t) {
        boolean r = tickers.remove(t.toUpperCase(Locale.ROOT));
        if (r) save();
        return r;
    }

    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String p : raw.toUpperCase(Locale.ROOT).split("[\\s,;]+")) {
            String t = p.trim().replace("$", "");
            int colon = t.indexOf(':');
            if (colon >= 0) t = t.substring(colon + 1); // NASDAQ:AAPL
            if (TICKER.matcher(t).matches() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private void save() { store.write(FILE, all()); }
}
