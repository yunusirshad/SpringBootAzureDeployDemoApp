package com.screener;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.screener.config.ScreenerProperties;
import com.screener.data.JsonStore;
import com.screener.data.ZoyaClient;
import com.screener.model.ZoyaCheck;
import com.screener.service.ZoyaService;

class ZoyaServiceTest {
    @TempDir Path dir;

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T14:00:00Z"));
    final AtomicReference<String> next = new AtomicReference<>("COMPLIANT");
    final AtomicInteger calls = new AtomicInteger();
    final ZoyaClient client = s -> {
        calls.incrementAndGet();
        String n = next.get();
        if (n == null) throw new IllegalStateException("timeout");
        return new ZoyaCheck(s, n, clock.instant(), "LIVE", null);
    };

    ZoyaService service() {
        ScreenerProperties p = new ScreenerProperties();
        p.setStoreDir(dir.toString());
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        return new ZoyaService(client, new JsonStore(p, m), clock, p);
    }

    @Test
    void goodReadIsKeptFor24Hours() {
        ZoyaService z = service();
        assertThat(z.check("AAA").source()).isEqualTo("LIVE");
        clock.advance(Duration.ofHours(23));
        ZoyaCheck c = z.check("AAA");
        assertThat(c.source()).isEqualTo("CACHE");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(z.isFreshCompliant(c)).isTrue();
        clock.advance(Duration.ofHours(2));
        assertThat(z.check("AAA").source()).isEqualTo("LIVE");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void failedReadDoesNotReplaceGoodRead() {
        ZoyaService z = service();
        z.check("AAA");
        next.set(null);
        ZoyaCheck forced = z.check("AAA", true);
        assertThat(forced.status()).isEqualTo("COMPLIANT");
        assertThat(forced.source()).isEqualTo("CACHE");
        assertThat(forced.error()).isEqualTo("timeout");
        assertThat(z.isFreshCompliant(forced)).isTrue();

        clock.advance(Duration.ofHours(25));
        ZoyaCheck stale = z.check("AAA");
        assertThat(stale.status()).isEqualTo("COMPLIANT");
        assertThat(stale.source()).isEqualTo("STALE");
        assertThat(z.isFreshCompliant(stale)).isFalse();

        next.set("NON_COMPLIANT");
        assertThat(z.check("AAA").status()).isEqualTo("NON_COMPLIANT");
    }

    @Test
    void neverReadIsUnknown() {
        next.set(null);
        ZoyaCheck c = service().check("BBB");
        assertThat(c.status()).isEqualTo("UNKNOWN");
        assertThat(c.source()).isEqualTo("NONE");
    }

    @Test
    void cacheSurvivesRestart() {
        service().check("AAA");
        next.set(null);
        ZoyaCheck c = service().check("AAA");
        assertThat(c.source()).isEqualTo("CACHE");
        assertThat(c.isCompliant()).isTrue();
    }
}
