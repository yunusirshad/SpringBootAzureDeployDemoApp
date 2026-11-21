package com.screener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.screener.model.Bar;
import com.screener.model.TradePlan;
import com.screener.service.PlanService;

class PlanServiceTest {
    static final ZoneId NY = ZoneId.of("America/New_York");
    final LocalDate today = LocalDate.of(2026, 11, 16);

    /** 25 bars, rising or falling by step; last bar is the prior day (today has no bar yet). */
    static List<Bar> bars(double start, double step, double lastClose) {
        List<Bar> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 10, 10);
        double c = start;
        for (int i = 0; i < 25; i++) {
            double close = i == 24 ? lastClose : c;
            out.add(new Bar(d.plusDays(i).atTime(9, 30).atZone(NY).toInstant(), close, close + 0.20, close - 0.20, close, 1_000_000, "DAY"));
            c += step;
        }
        return out;
    }

    @Test
    void longPlanSizedFromAccountAndStop() {
        // Uptrend, last close 12.00 with H 12.20, L 11.80 -> pivot 12.00, price above SMA but equal to pivot -> force long
        TradePlan p = PlanService.build("T", bars(9, 0.1, 12.0), today, 25_000, 1.0, "long");
        assertThat(p.side()).isEqualTo("LONG");
        assertThat(p.entry()).isEqualTo(12.21);
        assertThat(p.stop()).isEqualTo(12.0);
        assertThat(p.riskPerShare()).isEqualTo(0.21);
        // risk $250 / 0.21 = 1190 shares; cash cap 25000/12.21 = 2047
        assertThat(p.shares()).isEqualTo(1190);
        assertThat(p.target2()).isEqualTo(12.63);
        assertThat(p.state()).isEqualTo("WAITING");
    }

    @Test
    void sharesCappedByAccount() {
        TradePlan p = PlanService.build("T", bars(9, 0.1, 12.0), today, 2_000, 5.0, "long");
        assertThat(p.shares()).isEqualTo((int) Math.floor(2_000 / 12.21));
        assertThat(p.notes()).anyMatch(n -> n.contains("capped by the account"));
    }

    @Test
    void shortPlanMirrors() {
        TradePlan p = PlanService.build("T", bars(15, -0.1, 12.0), today, 25_000, 1.0, "short");
        assertThat(p.side()).isEqualTo("SHORT");
        assertThat(p.entry()).isEqualTo(11.79);
        assertThat(p.stop()).isEqualTo(12.0);
        assertThat(p.target2()).isEqualTo(11.37);
    }

    @Test
    void biasPicksTheSide() {
        // last close well above the 20-day average and above the pivot -> bullish -> long
        List<Bar> b = bars(9, 0.1, 12.0);
        Bar last = b.get(24);
        b.set(24, new Bar(last.time(), 11.5, 12.5, 11.4, 12.4, 1, "DAY"));
        TradePlan p = PlanService.build("T", b, today, 25_000, 1.0, null);
        assertThat(p.bias()).isEqualTo("BULLISH");
        assertThat(p.side()).isEqualTo("LONG");
    }

    @Test
    void tooFewBars() {
        assertThatThrownBy(() -> PlanService.build("T", bars(9, 0.1, 12).subList(0, 10), today, 1000, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
