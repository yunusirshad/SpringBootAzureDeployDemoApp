package com.screener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.screener.model.GexLevels;
import com.screener.model.OptionChain;
import com.screener.model.OptionContract;
import com.screener.service.GexCalculator;

class GexCalculatorTest {
    final LocalDate today = LocalDate.of(2026, 11, 16);
    final LocalDate exp = LocalDate.of(2026, 11, 20);

    @Test
    void wallsAndKingFromOpenInterest() {
        OptionChain chain = new OptionChain("T", 10.0, List.of(
                new OptionContract(true, 10, exp, 500, 0.5),
                new OptionContract(true, 11, exp, 5000, 0.5),
                new OptionContract(true, 12, exp, 800, 0.5),
                new OptionContract(false, 9, exp, 6000, 0.5),
                new OptionContract(false, 10, exp, 300, 0.5),
                new OptionContract(false, 8, exp, 100, 0.5)));
        GexLevels g = new GexCalculator().compute(chain, today);
        assertThat(g.callWall()).isEqualTo(11.0);
        assertThat(g.putWall()).isEqualTo(9.0);
        assertThat(g.king()).isIn(9.0, 11.0);
        assertThat(g.expirations()).containsExactly("2026-11-20");
    }

    @Test
    void noOpenInterestIsUnavailable() {
        OptionChain chain = new OptionChain("T", 10.0, List.of(new OptionContract(true, 10, exp, 0, 0.5)));
        assertThat(new GexCalculator().compute(chain, today).error()).isNotNull();
    }
}
