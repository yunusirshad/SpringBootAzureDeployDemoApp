package com.screener.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.screener.config.AppConfig;
import com.screener.data.JsonStore;
import com.screener.model.CoreState;
import com.screener.model.ScreenRow;

/**
 * Core stocks: the names that passed the screen with a fresh Zoya COMPLIANT read, kept for the rest of the
 * New York day even if a later screen drops them on a ratio. They are stored as the same full screener row.
 * A name leaves the core when its Zoya read is no longer fresh and compliant, and the list starts over at
 * the New York date change.
 */
@Service
public class CoreStocksService {
    private static final String FILE = "core-stocks.json";

    private final JsonStore store;
    private final Clock clock;
    private final ZoyaService zoya;
    private CoreState state;

    public CoreStocksService(JsonStore store, Clock clock, ZoyaService zoya) {
        this.store = store;
        this.clock = clock;
        this.zoya = zoya;
        this.state = store.read(FILE, new TypeReference<CoreState>() {}).orElse(CoreState.forDay(today()));
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AppConfig.NEW_YORK));
    }

    /** Adds or refreshes rows. Rows without a fresh compliant Zoya read are not added and are removed if present. */
    public synchronized List<ScreenRow> merge(Collection<ScreenRow> rows) {
        rollDay();
        List<ScreenRow> added = new ArrayList<>();
        for (ScreenRow row : rows) {
            if (zoya.isFreshCompliant(row.zoya())) {
                state.rows().put(row.symbol(), row);
                added.add(row);
            } else {
                state.rows().remove(row.symbol());
            }
        }
        store.write(FILE, state);
        return added;
    }

    /** Refreshes a core name's row without adding new names (a re-screen that drops a ratio keeps the name). */
    public synchronized void refreshIfPresent(ScreenRow row) {
        rollDay();
        if (!state.rows().containsKey(row.symbol())) return;
        if (zoya.isFreshCompliant(row.zoya())) state.rows().put(row.symbol(), row);
        else state.rows().remove(row.symbol());
        store.write(FILE, state);
    }

    public synchronized boolean remove(String symbol) {
        rollDay();
        boolean had = state.rows().remove(symbol) != null;
        store.write(FILE, state);
        return had;
    }

    public synchronized List<ScreenRow> list() {
        rollDay();
        boolean changed = state.rows().values().removeIf(r -> !zoya.isFreshCompliant(r.zoya()));
        if (changed) store.write(FILE, state);
        return List.copyOf(state.rows().values());
    }

    public synchronized LocalDate day() {
        rollDay();
        return state.nyDate();
    }

    private void rollDay() {
        LocalDate t = today();
        if (!t.equals(state.nyDate())) {
            state = CoreState.forDay(t);
            store.write(FILE, state);
        }
    }
}
