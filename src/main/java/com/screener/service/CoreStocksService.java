package com.screener.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.screener.config.AppConfig;
import com.screener.data.JsonStore;
import com.screener.model.CoreState;
import com.screener.model.ScreenRow;

/**
 * Core stocks, two kinds, both stored as the same full screener row.
 *
 * Screen core: the names that passed the screen with a fresh Zoya COMPLIANT read, kept for the rest of the
 * New York day even if a later screen drops them on a ratio. A name leaves when its Zoya read is no longer
 * fresh and compliant, and the list starts over at the New York date change.
 *
 * My core: names you add yourself. No gate and no Zoya rule applies to them (they can be non-compliant);
 * they stay, across days, until you remove them. Their rows are refreshed by each screen.
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

    /** Removes the name from both my core and today's screen core. */
    public synchronized boolean remove(String symbol) {
        rollDay();
        boolean had = state.rows().remove(symbol) != null;
        had |= state.mine().remove(symbol) != null;
        store.write(FILE, state);
        return had;
    }

    /** Adds (or refreshes) one of your own core names. No compliance check. */
    public synchronized void putMine(ScreenRow row) {
        rollDay();
        state.mine().put(row.symbol(), row);
        store.write(FILE, state);
    }

    /** Refreshes your own name's row if it is still on your list. */
    public synchronized void refreshMine(ScreenRow row) {
        rollDay();
        if (state.mine().containsKey(row.symbol())) {
            state.mine().put(row.symbol(), row);
            store.write(FILE, state);
        }
    }

    public synchronized List<String> mineSymbols() {
        return List.copyOf(state.mine().keySet());
    }

    public synchronized boolean isMine(String symbol) {
        return state.mine().containsKey(symbol);
    }

    /** Your names first, then the screen core (a name on both is listed once, as yours). */
    public synchronized List<ScreenRow> list() {
        rollDay();
        boolean changed = state.rows().values().removeIf(r -> !zoya.isFreshCompliant(r.zoya()));
        if (changed) store.write(FILE, state);
        Map<String, ScreenRow> all = new LinkedHashMap<>(state.mine());
        state.rows().forEach(all::putIfAbsent);
        return List.copyOf(all.values());
    }

    public synchronized LocalDate day() {
        rollDay();
        return state.nyDate();
    }

    private void rollDay() {
        LocalDate t = today();
        if (!t.equals(state.nyDate())) {
            state = state.nextDay(t);
            store.write(FILE, state);
        }
    }
}
