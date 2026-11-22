package com.personalscreener.analysis;

import com.personalscreener.data.Bar;
import com.personalscreener.service.BacktestService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuantumShiftTest {

    /** 150 quiet bars, then a strong, high-volume ramp. */
    static List<Bar> coilThenBreak(int dir) {
        List<Bar> b = new ArrayList<>();
        long t = 1_760_000_000L;
        double p = 50;
        for (int i = 0; i < 150; i++) {
            double c = p + Math.sin(i / 3.0) * 0.4;
            b.add(new Bar(t + i * 3600L, c, c + 0.3, c - 0.3, c, 1000));
        }
        double c = b.get(b.size() - 1).close();
        for (int i = 0; i < 40; i++) {
            double o = c;
            c = c * (1 + dir * 0.012);
            b.add(new Bar(t + (150 + i) * 3600L, o, Math.max(o, c) + 0.2, Math.min(o, c) - 0.2, c, 3000));
        }
        return b;
    }

    @Test
    void firesShiftUpOutOfACoil() {
        List<Bar> b = coilThenBreak(1);
        QuantumShift.Series s = QuantumShift.compute(b, 60);
        int first = -1;
        for (int i = 0; i < s.length(); i++) if (s.signal()[i] != 0) { first = i; break; }
        assertTrue(first >= 150, "no signal during the quiet stretch, first was " + first);
        assertEquals(1, s.signal()[first]);
        assertEquals(1, s.regime()[s.length() - 1]);
        assertTrue(s.energy()[first] >= 60);
    }

    @Test
    void firesShiftDownOnTheMirror() {
        QuantumShift.Series s = QuantumShift.compute(coilThenBreak(-1), 60);
        int first = -1;
        for (int i = 0; i < s.length(); i++) if (s.signal()[i] != 0) { first = i; break; }
        assertEquals(-1, s.signal()[first]);
    }

    @Test
    void backtestTakesTheLongAndWins() throws Exception {
        Method m = BacktestService.class.getDeclaredMethod("simulate", String.class, List.class, BacktestService.Params.class);
        m.setAccessible(true);
        var p = new BacktestService.Params(365, "60m", 60, true, 2, 2.5, 35, 5);
        var r = (BacktestService.StockResult) m.invoke(null, "TEST", coilThenBreak(1), p);
        assertTrue(r.trades() >= 1);
        assertEquals("Long", r.tradeList().get(0).side());
        assertTrue(r.totalReturnPct() > 0);
    }
}
