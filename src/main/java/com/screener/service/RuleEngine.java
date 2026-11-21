package com.screener.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.screener.config.ScreenerProperties;
import com.screener.model.Fundamentals;
import com.screener.model.GateResult;

/**
 * The ratio gates. Every gate removes a name when it fails or has no number, except cash/debt with zero
 * debt, which passes (no debt to cover).
 */
@Component
public class RuleEngine {

    public static final String PRICE = "Price";
    public static final String MARKET_CAP = "Market cap";
    public static final String UPSIDE = "Target upside";
    public static final String DEBT_EQUITY = "Debt/equity";
    public static final String DEBT_ASSETS = "Debt/assets";
    public static final String DEBT_CAPITAL = "Debt/capital";
    public static final String CASH_DEBT = "Cash/debt";
    public static final String REVENUE_GROWTH = "Revenue growth";

    private final ScreenerProperties.Rules r;

    public RuleEngine(ScreenerProperties props) {
        this.r = props.getRules();
    }

    public boolean inPriceBand(Double price) {
        return price != null && price >= r.getMinPrice() && price <= r.getMaxPrice();
    }

    public boolean overMarketCap(Double cap) {
        return cap != null && cap > r.getMinMarketCap();
    }

    public record Ratios(Double upside, Double debtToEquity, Double debtToAssets, Double debtToCapital, Double cashToDebt) {}

    public Ratios ratios(Fundamentals f) {
        Double upside = (f.targetMeanPrice() != null && f.price() != null && f.price() > 0)
                ? f.targetMeanPrice() / f.price() - 1 : null;
        Double debt = f.totalDebt();
        Double eq = f.totalEquity();
        Double de = (debt != null && eq != null && eq > 0) ? debt / eq : null;
        Double da = (debt != null && f.totalAssets() != null && f.totalAssets() > 0) ? debt / f.totalAssets() : null;
        Double dc = (debt != null && eq != null && eq > 0) ? debt / (debt + eq) : null;
        Double cd = null;
        if (debt != null && f.totalCash() != null) {
            cd = debt <= 0 ? Double.POSITIVE_INFINITY : f.totalCash() / debt;
        }
        return new Ratios(upside, de, da, dc, cd);
    }

    public List<GateResult> evaluate(Fundamentals f) {
        Ratios x = ratios(f);
        List<GateResult> g = new ArrayList<>();
        g.add(new GateResult(PRICE, f.price(), money(r.getMinPrice()) + "–" + money(r.getMaxPrice()), inPriceBand(f.price())));
        g.add(new GateResult(MARKET_CAP, f.marketCap(), "> " + big(r.getMinMarketCap()), overMarketCap(f.marketCap())));
        g.add(over(UPSIDE, x.upside(), r.getMinUpside(), true));
        g.add(under(DEBT_EQUITY, x.debtToEquity(), r.getMaxDebtEquity()));
        g.add(under(DEBT_ASSETS, x.debtToAssets(), r.getMaxDebtAssets()));
        g.add(under(DEBT_CAPITAL, x.debtToCapital(), r.getMaxDebtCapital()));
        if (x.cashToDebt() != null && x.cashToDebt().isInfinite()) {
            g.add(new GateResult(CASH_DEBT, null, "> " + trim(r.getMinCashDebt()) + " (no debt)", true));
        } else {
            g.add(over(CASH_DEBT, x.cashToDebt(), r.getMinCashDebt(), false));
        }
        g.add(over(REVENUE_GROWTH, f.revenueGrowth(), r.getMinRevenueGrowth(), true));
        return g;
    }

    private static GateResult over(String name, Double v, double min, boolean pct) {
        return new GateResult(name, v, "> " + (pct ? pct(min) : trim(min)), v != null && v > min);
    }

    private static GateResult under(String name, Double v, double max) {
        return new GateResult(name, v, "< " + trim(max), v != null && v < max);
    }

    private static String money(double v) {
        return String.format("$%.2f", v);
    }

    private static String pct(double v) {
        return trim(v * 100) + "%";
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String big(double v) {
        if (v >= 1e9) return "$" + trim(v / 1e9) + "B";
        if (v >= 1e6) return "$" + trim(v / 1e6) + "M";
        return "$" + trim(v);
    }
}
