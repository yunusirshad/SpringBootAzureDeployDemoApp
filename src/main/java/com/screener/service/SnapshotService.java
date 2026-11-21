package com.screener.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.screener.data.JsonStore;
import com.screener.model.ScreenRow;
import com.screener.model.Snapshot;
import com.screener.model.SnapshotEntry;
import com.screener.model.TradePlan;

/**
 * The daily snapshot for the bot: side, bias, state, Zoya and quality for every name on the screen list
 * and in the core, one file per New York day (snapshot-YYYY-MM-DD.json). Your own core names are included
 * whatever their Zoya status, with mine=true, so the bot can tell them apart.
 */
@Service
public class SnapshotService {

    private final ScreenerService screener;
    private final CoreStocksService core;
    private final PlanService plans;
    private final JsonStore store;
    private final Clock clock;

    public SnapshotService(ScreenerService screener, CoreStocksService core, PlanService plans, JsonStore store, Clock clock) {
        this.screener = screener;
        this.core = core;
        this.plans = plans;
        this.store = store;
        this.clock = clock;
    }

    public synchronized Snapshot build() {
        LocalDate day = core.today();
        Map<String, ScreenRow> rows = new LinkedHashMap<>();
        List<ScreenRow> coreRows = core.list();
        coreRows.forEach(r -> rows.put(r.symbol(), r));
        if (day.equals(screener.latest().nyDate())) screener.latest().rows().forEach(r -> rows.putIfAbsent(r.symbol(), r));

        List<SnapshotEntry> entries = new ArrayList<>();
        for (ScreenRow r : rows.values()) {
            boolean isCore = coreRows.stream().anyMatch(c -> c.symbol().equals(r.symbol()));
            boolean mine = core.isMine(r.symbol());
            Integer qs = r.quality() == null ? null : r.quality().score();
            String qg = r.quality() == null ? null : r.quality().grade();
            String z = r.zoya() == null ? "UNKNOWN" : r.zoya().status();
            try {
                TradePlan p = plans.analyze(r.symbol(), null, null, null);
                entries.add(new SnapshotEntry(r.symbol(), isCore, mine, p.side(), p.bias(), p.state(), z, qs, qg, p.price(),
                        p.entry(), p.stop(), p.target1(), p.shares(), r.earningsDate(), null));
            } catch (Exception e) {
                entries.add(new SnapshotEntry(r.symbol(), isCore, mine, "NONE", "UNKNOWN", "ERROR", z, qs, qg, r.price(),
                        null, null, null, 0, r.earningsDate(), e.getMessage()));
            }
        }
        Snapshot snap = new Snapshot(day, clock.instant(), entries);
        store.write(fileFor(day), snap);
        return snap;
    }

    /** Today's snapshot, built on first ask. */
    public Snapshot today() {
        LocalDate day = core.today();
        return store.read(fileFor(day), new TypeReference<Snapshot>() {}).orElseGet(this::build);
    }

    public Snapshot forDate(LocalDate day) {
        if (day.equals(core.today())) return today();
        return store.read(fileFor(day), new TypeReference<Snapshot>() {})
                .orElseThrow(() -> new IllegalArgumentException("No snapshot for " + day));
    }

    private static String fileFor(LocalDate d) {
        return "snapshot-" + d + ".json";
    }
}
