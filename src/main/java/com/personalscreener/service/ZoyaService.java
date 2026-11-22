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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Compliance with a cache, so each name is checked at most once per cache window. */
@Service
public class ZoyaService {

    private static final Logger log = LoggerFactory.getLogger(ZoyaService.class);
    private static final String FILE = "zoya-cache.json";

    public static class Entry {
        public String status;
        public String reportDate;
        public String checkedAt;
    }

    private final ZoyaClient client;
    private final JsonStore store;
    private final ScreenerProperties props;
    private final Map<String, Entry> cache;

    public ZoyaService(ZoyaClient client, JsonStore store, ScreenerProperties props) {
        this.client = client;
        this.store = store;
        this.props = props;
        this.cache = new ConcurrentHashMap<>(store.read(FILE, new TypeReference<Map<String, Entry>>() {}, new HashMap<>()));
    }

    public boolean configured() { return client.configured(); }

    public Entry get(String symbol) {
        Entry e = cache.get(symbol);
        boolean fresh = e != null && e.checkedAt != null
                && OffsetDateTime.parse(e.checkedAt).isAfter(OffsetDateTime.now().minus(Duration.ofHours(props.getZoya().getCacheHours())));
        if (fresh || !client.configured()) {
            if (e != null) return e;
            Entry none = new Entry();
            none.status = ZoyaClient.Status.NOT_CHECKED.name();
            return none;
        }
        try {
            ZoyaClient.Report r = client.check(symbol);
            Entry n = new Entry();
            n.status = r.status().name();
            n.reportDate = r.reportDate();
            n.checkedAt = OffsetDateTime.now().toString();
            cache.put(symbol, n);
            return n;
        } catch (Exception ex) {
            log.warn("Zoya check failed for {}: {}", symbol, ex.getMessage());
            if (e != null) return e;
            Entry none = new Entry();
            none.status = ZoyaClient.Status.NOT_CHECKED.name();
            return none;
        }
    }

    public void persist() { store.write(FILE, cache); }
}
