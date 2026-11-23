package com.personalscreener.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.data.ZoyaClient;
import com.personalscreener.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compliance with a cache saved to {@code zoya-cache.json}: each ticker is looked up on Zoya at most once per
 * {@code screener.zoya.cache-hours} (24 by default), survives restarts, and two screens asking for the same ticker
 * at once share one lookup. A failed lookup is not cached, but the ticker waits 15 minutes before it is tried again.
 */
@Service
public class ZoyaService {

    private static final Logger log = LoggerFactory.getLogger(ZoyaService.class);
    private static final String FILE = "zoya-cache.json";
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(15);

    public static class Entry {
        public String symbol;
        public String status;
        public String reportDate;
        public String checkedAt;
        public String source;
        public String url;
        /** True when this came from the cache rather than a fresh lookup. Not saved. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean cached;
    }

    private final ZoyaClient client;
    private final JsonStore store;
    private final ScreenerProperties props;
    private final Map<String, Entry> cache;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> failedAt = new ConcurrentHashMap<>();

    public ZoyaService(ZoyaClient client, JsonStore store, ScreenerProperties props) {
        this.client = client;
        this.store = store;
        this.props = props;
        this.cache = new ConcurrentHashMap<>(store.read(FILE, new TypeReference<Map<String, Entry>>() {}, new HashMap<>()));
    }

    public boolean configured() { return client.configured(); }

    public String source() { return props.getZoya().getSource(); }

    public Entry get(String symbol) { return get(symbol, false); }

    /** @param force skip the cache and look the ticker up again now */
    public Entry get(String symbol, boolean force) {
        String key = symbol.toUpperCase(Locale.ROOT);
        Entry e = cache.get(key);
        if (!force && fresh(e)) return asCached(e);
        if (!client.configured()) return e != null ? asCached(e) : notChecked(key);
        OffsetDateTime failed = failedAt.get(key);
        if (!force && failed != null && failed.isAfter(OffsetDateTime.now().minus(RETRY_AFTER_FAILURE))) {
            return e != null ? asCached(e) : notChecked(key);
        }

        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            Entry again = cache.get(key);  // another thread may have just fetched it
            if (!force && fresh(again)) return asCached(again);
            try {
                ZoyaClient.Report r = client.check(key);
                Entry n = new Entry();
                n.symbol = key;
                n.status = r.status().name();
                n.reportDate = r.reportDate();
                n.checkedAt = OffsetDateTime.now().toString();
                n.source = r.source();
                n.url = "page".equals(r.source()) ? props.getZoya().getPageUrl() + key.toLowerCase(Locale.ROOT) : null;
                cache.put(key, n);
                failedAt.remove(key);
                persist();
                log.info("Zoya {}: {}", key, n.status);
                return n;
            } catch (Exception ex) {
                log.warn("Zoya check failed for {}: {}", key, ex.getMessage());
                failedAt.put(key, OffsetDateTime.now());
                return again != null ? asCached(again) : notChecked(key);
            }
        }
    }

    /** Everything in the cache, oldest-checked first is not guaranteed. */
    public Map<String, Entry> all() { return Map.copyOf(cache); }

    private boolean fresh(Entry e) {
        if (e == null || e.checkedAt == null || ZoyaClient.Status.NOT_CHECKED.name().equals(e.status)) return false;
        try {
            return OffsetDateTime.parse(e.checkedAt)
                    .isAfter(OffsetDateTime.now().minus(Duration.ofHours(props.getZoya().getCacheHours())));
        } catch (Exception ex) {
            return false;
        }
    }

    private static Entry asCached(Entry e) {
        Entry c = new Entry();
        c.symbol = e.symbol;
        c.status = e.status;
        c.reportDate = e.reportDate;
        c.checkedAt = e.checkedAt;
        c.source = e.source;
        c.url = e.url;
        c.cached = true;
        return c;
    }

    private static Entry notChecked(String symbol) {
        Entry none = new Entry();
        none.symbol = symbol;
        none.status = ZoyaClient.Status.NOT_CHECKED.name();
        return none;
    }

    public synchronized void persist() { store.write(FILE, cache); }
}
