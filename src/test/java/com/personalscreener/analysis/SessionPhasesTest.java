package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.market.MarketClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionPhasesTest {

    /** Fills one session with 5-minute bars that walk through the given price waypoints. */
    static void session(List<Bar> out, SessionPhases.Name n, LocalDate td, double[] path, double vol) {
        long[] w = SessionPhases.window(n, td);
        int count = (int) ((w[1] - w[0]) / 300);
        for (int i = 0; i < count; i++) {
            double f = (double) i / (count - 1) * (path.length - 1);
            int k = Math.min(path.length - 2, (int) f);
            double a = path[k] + (path[k + 1] - path[k]) * (f - k);
            double f2 = (double) Math.min(count - 1, i + 1) / (count - 1) * (path.length - 1);
            int k2 = Math.min(path.length - 2, (int) f2);
            double b = path[k2] + (path[k2 + 1] - path[k2]) * (f2 - k2);
            out.add(new Bar(w[0] + i * 300L, a, Math.max(a, b) + 0.02, Math.min(a, b) - 0.02, b, vol));
        }
    }

    static List<Bar> normalDays(LocalDate from, int days) {
        List<Bar> b = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            LocalDate td = from.plusDays(d);
            session(b, SessionPhases.Name.ASIA, td, new double[]{100, 100.6, 99.6, 100}, 100);
            session(b, SessionPhases.Name.LONDON, td, new double[]{100, 100.7, 99.5, 100}, 100);
            session(b, SessionPhases.Name.NEW_YORK, td, new double[]{100, 100.8, 99.4, 100}, 100);
        }
        return b;
    }

    @Test
    void readsAllFourPhasesWithDirection() {
        LocalDate start = LocalDate.of(2026, 11, 2);
        List<Bar> bars = normalDays(start, 10);
        LocalDate td = start.plusDays(10);
        // Asia: tight balance -> accumulation
        session(bars, SessionPhases.Name.ASIA, td, new double[]{100, 100.25, 99.85, 100.1}, 100);
        // London: runs above Asia's high, then dumps back inside -> bear manipulation
        session(bars, SessionPhases.Name.LONDON, td, new double[]{100.1, 100.9, 100.1, 99.7}, 100);
        // New York: one-way rally -> bull distribution
        session(bars, SessionPhases.Name.NEW_YORK, td, new double[]{99.7, 100.4, 101.2, 102.2}, 100);
        long end = SessionPhases.window(SessionPhases.Name.NEW_YORK, td)[1] + 1;
        List<SessionPhases.Session> s = SessionPhases.read(bars, end);
        int n = s.size();
        SessionPhases.Session asia = s.get(n - 3), london = s.get(n - 2), ny = s.get(n - 1);

        assertEquals("Asia", asia.session());
        assertTrue(asia.label().endsWith("Accumulation"), asia.label());
        assertTrue(asia.accumulation().pct() >= 80, "acc " + asia.accumulation().pct());

        assertEquals("Bear Manipulation", london.label());
        assertEquals(100, london.manipulation().pct());

        assertEquals("Bull Distribution", ny.label());
        assertTrue(ny.distribution().pct() >= 90);
        assertFalse(ny.live());
    }

    @Test
    void absorptionIsHeavyVolumeGoingNowhere() {
        LocalDate start = LocalDate.of(2026, 11, 2);
        List<Bar> bars = normalDays(start, 10);
        LocalDate td = start.plusDays(10);
        session(bars, SessionPhases.Name.ASIA, td, new double[]{100, 100.6, 99.6, 100}, 100);
        session(bars, SessionPhases.Name.LONDON, td, new double[]{100, 100.6, 99.6, 100}, 100);
        // NY: 3x volume, range inside London's, closes near the high of its range
        session(bars, SessionPhases.Name.NEW_YORK, td, new double[]{100, 99.7, 100.2, 99.75, 100.1}, 300);
        List<SessionPhases.Session> s = SessionPhases.read(bars, Long.MAX_VALUE);
        SessionPhases.Session ny = s.get(s.size() - 1);
        assertEquals("Absorption", ny.label().split(" ")[1], ny.label() + " " + ny);
        assertEquals("Bull", ny.absorption().dir());
        assertTrue(ny.absorption().pct() >= 80, "abs " + ny.absorption().pct());
    }

    @Test
    void slotsCoverTheClock() {
        long t = LocalDate.of(2026, 11, 23).atTime(19, 0).atZone(MarketClock.ET).toEpochSecond();
        var s = SessionPhases.slot(t);
        assertEquals(SessionPhases.Name.ASIA, s.getKey());
        assertEquals(LocalDate.of(2026, 11, 24), s.getValue());
        long gap = LocalDate.of(2026, 11, 23).atTime(17, 30).atZone(MarketClock.ET).toEpochSecond();
        assertNull(SessionPhases.slot(gap));
    }
}
