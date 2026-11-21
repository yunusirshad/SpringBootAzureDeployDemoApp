package com.screener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.screener.config.ScreenerProperties;
import com.screener.model.Fundamentals;
import com.screener.model.GateResult;
import com.screener.service.RuleEngine;

class RuleEngineTest {
    private final RuleEngine engine = new RuleEngine(new ScreenerProperties());

    static Fundamentals f(double price, double cap, double target, double debt, double cash, double assets, double equity, double growth) {
        return new Fundamentals("T", "T", "NYSE", price, cap, target, debt, cash, assets, equity, growth, null,
                null, null, null, null, null, null);
    }

    private Map<String, Boolean> gates(Fundamentals x) {
        List<GateResult> g = engine.evaluate(x);
        return g.stream().collect(Collectors.toMap(GateResult::gate, GateResult::pass));
    }

    @Test
    void cleanNamePassesEveryGate() {
        assertThat(gates(f(10, 1e9, 13, 50e6, 100e6, 1e9, 800e6, 0.10)).values()).containsOnly(true);
    }

    @Test
    void priceBandIsInclusiveAt150And20() {
        assertThat(engine.inPriceBand(1.50)).isTrue();
        assertThat(engine.inPriceBand(20.00)).isTrue();
        assertThat(engine.inPriceBand(1.49)).isFalse();
        assertThat(engine.inPriceBand(20.01)).isFalse();
        assertThat(engine.inPriceBand(null)).isFalse();
    }

    @Test
    void marketCapMustBeOver300M() {
        assertThat(engine.overMarketCap(300e6)).isFalse();
        assertThat(engine.overMarketCap(300e6 + 1)).isTrue();
    }

    @Test
    void eachRatioFailsOnItsOwn() {
        // upside exactly 25% is not "over" 25%
        assertThat(gates(f(10, 1e9, 12.5, 50e6, 100e6, 1e9, 800e6, 0.10)).get(RuleEngine.UPSIDE)).isFalse();
        // D/E 0.5 fails, D/C = 400/1200 = 0.33 fails, D/A 400/2000 = 0.2 passes, cash/debt 0.25 fails
        Map<String, Boolean> g = gates(f(10, 1e9, 15, 400e6, 100e6, 2e9, 800e6, 0.10));
        assertThat(g.get(RuleEngine.DEBT_EQUITY)).isFalse();
        assertThat(g.get(RuleEngine.DEBT_CAPITAL)).isFalse();
        assertThat(g.get(RuleEngine.DEBT_ASSETS)).isTrue();
        assertThat(g.get(RuleEngine.CASH_DEBT)).isFalse();
        // debt/assets 0.35 fails
        assertThat(gates(f(10, 1e9, 15, 350e6, 400e6, 1e9, 5e9, 0.10)).get(RuleEngine.DEBT_ASSETS)).isFalse();
        // revenue growth 5% is not over 5%
        assertThat(gates(f(10, 1e9, 15, 0, 100e6, 1e9, 800e6, 0.05)).get(RuleEngine.REVENUE_GROWTH)).isFalse();
    }

    @Test
    void zeroDebtPassesCashToDebt() {
        assertThat(gates(f(10, 1e9, 15, 0, 0, 1e9, 800e6, 0.10)).get(RuleEngine.CASH_DEBT)).isTrue();
    }

    @Test
    void missingDataFails() {
        Fundamentals x = new Fundamentals("T", "T", "NYSE", 10.0, 1e9, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        Map<String, Boolean> g = gates(x);
        assertThat(g.get(RuleEngine.UPSIDE)).isFalse();
        assertThat(g.get(RuleEngine.DEBT_EQUITY)).isFalse();
        assertThat(g.get(RuleEngine.CASH_DEBT)).isFalse();
        assertThat(g.get(RuleEngine.REVENUE_GROWTH)).isFalse();
    }

    @Test
    void negativeEquityFailsDebtEquity() {
        assertThat(gates(f(10, 1e9, 15, 10e6, 100e6, 1e9, -5e6, 0.10)).get(RuleEngine.DEBT_EQUITY)).isFalse();
    }
}
