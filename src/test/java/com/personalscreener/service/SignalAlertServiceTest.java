package com.personalscreener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.analysis.SetupScore;
import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.StockRow;
import com.personalscreener.store.JsonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalAlertServiceTest {

    @TempDir Path dir;

    private SignalAlertService at(int h, int m) throws Exception {
        ScreenerProperties props = new ScreenerProperties();
        props.setDataDir(dir.toString());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Clock c = Clock.fixed(ZonedDateTime.of(2026, 11, 24, h, m, 0, 0, MarketClock.ET).toInstant(), MarketClock.ET);
        return new SignalAlertService(new JsonStore(props, mapper), new MarketClock(c), new Notifier(mapper, props));
    }

    private static StockRow row(String sym, String signal) {
        StockRow r = new StockRow();
        r.symbol = sym;
        r.signal = signal;
        r.price = 10.0;
        r.setupStop = 9.5;
        r.setupTarget = 11.0;
        r.rewardRisk = 2.0;
        return r;
    }

    @Test
    void buyAndSellNowAlertOncePerDay() throws Exception {
        SignalAlertService s = at(11, 0);
        var first = s.check(List.of(row("AAA", SetupScore.BUY_NOW), row("BBB", SetupScore.SELL_NOW), row("CCC", SetupScore.READY)));
        assertEquals(2, first.size());
        assertEquals("BUY NOW AAA at $10.00", SignalAlertService.title(first.get(0)));
        assertTrue(SignalAlertService.body(first.get(0)).contains("Stop $9.50 · target $11.00"));
        assertTrue(s.check(List.of(row("AAA", SetupScore.BUY_NOW))).isEmpty(), "same signal, same day: no repeat");
    }

    @Test
    void quietOutsideMarketHours() throws Exception {
        assertTrue(at(8, 0).check(List.of(row("AAA", SetupScore.BUY_NOW))).isEmpty());
        assertTrue(at(16, 30).check(List.of(row("AAA", SetupScore.BUY_NOW))).isEmpty());
    }
}
