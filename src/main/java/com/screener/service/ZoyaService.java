package com.screener.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.screener.config.ScreenerProperties;
import com.screener.data.JsonStore;
import com.screener.data.ZoyaClient;
import com.screener.model.ZoyaCheck;

/**
 * Live Zoya check with a 24-hour cache. A good (successful) read is kept for 24 hours and reused.
 * A failed read never replaces a good one: inside 24 hours the cached read is used as is; past 24 hours
 * it is shown as STALE, which does not count as compliant for the screen list or core stocks.
 */
@Service
public class ZoyaService {
    private static final Logger log = LoggerFactory.getLogger(ZoyaService.class);
    private static final String FILE = "zoya-cache.json";

    private final ZoyaClient client;
    private final JsonStore store;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, ZoyaCheck> cache = new ConcurrentHashMap<>();

    public ZoyaService(ZoyaClient client, JsonStore store, Clock clock, ScreenerProperties props) {
        this.client = client;
        this.store = store;
        this.clock = clock;
        this.ttl = Duration.ofHours(props.getZoya().getCacheHours());
        store.read(FILE, new TypeReference<Map<String, ZoyaCheck>>() {}).ifPresent(cache::putAll);
    }

    public ZoyaCheck check(String symbol) {
        return check(symbol, false);
    }

    public ZoyaCheck check(String symbol, boolean forceLive) {
        ZoyaCheck good = cache.get(symbol);
        boolean fresh = good != null && isFresh(good);
        if (fresh && !forceLive) return good.withSource("CACHE", null);
        try {
            ZoyaCheck live = client.check(symbol);
            ZoyaCheck stored = new ZoyaCheck(symbol, live.status(), clock.instant(), "LIVE", null);
            cache.put(symbol, stored);
            store.write(FILE, cache);
            return stored;
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.debug("Zoya read for {} failed: {}", symbol, err);
            if (good == null) return new ZoyaCheck(symbol, "UNKNOWN", null, "NONE", err);
            return good.withSource(fresh ? "CACHE" : "STALE", err);
        }
    }

    public boolean isFresh(ZoyaCheck c) {
        return c.checkedAt() != null && Duration.between(c.checkedAt(), clock.instant()).compareTo(ttl) < 0;
    }

    /** Re-reads the clock: a CACHE/LIVE label from earlier may have aged past 24 hours. */
    public boolean isFreshCompliant(ZoyaCheck c) {
        return c != null && c.isCompliant() && c.isFresh() && isFresh(c);
    }
}
