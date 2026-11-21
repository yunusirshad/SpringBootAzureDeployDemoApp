package com.screener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.screener.config.ScreenerProperties;
import com.screener.data.JsonStore;
import com.screener.model.ScreenRow;
import com.screener.model.ZoyaCheck;
import com.screener.service.CoreStocksService;
import com.screener.service.ZoyaService;

class CoreStocksServiceTest {
    @TempDir Path dir;
    // 15:00 New York on a Monday
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T20:00:00Z"));

    CoreStocksService service() {
        ScreenerProperties p = new ScreenerProperties();
        p.setStoreDir(dir.toString());
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonStore store = new JsonStore(p, m);
        ZoyaService z = new ZoyaService(s -> { throw new IllegalStateException("offline"); }, store, clock, p);
        return new CoreStocksService(store, clock, z);
    }

    ScreenRow row(String sym, String zoya) {
        return new ScreenRow(sym, sym, "NYSE", 10.0, 1e9, 13.0, 0.3, 0.1, 0.1, 0.1, 2.0, 0.1, null, List.of(), true,
                new ZoyaCheck(sym, zoya, clock.instant(), "LIVE", null), null, null, true, List.of(), clock.instant());
    }

    @Test
    void screenCoreIsCompliantOnlyAndResetsAtTheNewYorkDay() {
        CoreStocksService c = service();
        c.merge(List.of(row("AAA", "COMPLIANT"), row("BBB", "NON_COMPLIANT")));
        assertThat(c.list()).extracting(ScreenRow::symbol).containsExactly("AAA");
        clock.advance(Duration.ofHours(10)); // 06:00Z = 01:00 NY next day
        assertThat(c.list()).isEmpty();
    }

    @Test
    void myNamesCanBeNonCompliantAndSurviveTheDayAndRestart() {
        CoreStocksService c = service();
        c.putMine(row("BBB", "NON_COMPLIANT"));
        c.merge(List.of(row("AAA", "COMPLIANT")));
        assertThat(c.list()).extracting(ScreenRow::symbol).containsExactly("BBB", "AAA");
        clock.advance(Duration.ofDays(2));
        CoreStocksService restarted = service();
        assertThat(restarted.list()).extracting(ScreenRow::symbol).containsExactly("BBB");
        assertThat(restarted.isMine("BBB")).isTrue();
        assertThat(restarted.remove("BBB")).isTrue();
        assertThat(restarted.list()).isEmpty();
    }

    @Test
    void screenMergeDoesNotDropMyNonCompliantName() {
        CoreStocksService c = service();
        c.putMine(row("BBB", "NON_COMPLIANT"));
        c.merge(List.of(row("BBB", "NON_COMPLIANT")));
        assertThat(c.mineSymbols()).containsExactly("BBB");
        assertThat(c.list()).extracting(ScreenRow::symbol).containsExactly("BBB");
    }
}
