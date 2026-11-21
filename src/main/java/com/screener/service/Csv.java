package com.screener.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import com.screener.config.AppConfig;
import com.screener.model.Bar;
import com.screener.model.ScreenRow;
import com.screener.model.SnapshotEntry;

/** CSV for the public exports. */
public final class Csv {
    private Csv() {}

    public static String screen(List<ScreenRow> rows) {
        StringBuilder sb = new StringBuilder("symbol,name,exchange,price,market_cap,target_mean,upside,debt_equity,"
                + "debt_assets,debt_capital,cash_debt,revenue_growth,earnings_date,zoya,zoya_checked_at,zoya_source,"
                + "quality_score,quality_grade,call_wall,king,put_wall,passed\n");
        for (ScreenRow r : rows) {
            line(sb, r.symbol(), r.name(), r.exchange(), r.price(), r.marketCap(), r.targetMeanPrice(), r.upside(),
                    r.debtToEquity(), r.debtToAssets(), r.debtToCapital(), r.cashToDebt(), r.revenueGrowth(),
                    r.earningsDate(), r.zoya() == null ? null : r.zoya().status(),
                    r.zoya() == null ? null : r.zoya().checkedAt(), r.zoya() == null ? null : r.zoya().source(),
                    r.quality() == null ? null : r.quality().score(), r.quality() == null ? null : r.quality().grade(),
                    r.gex() == null ? null : r.gex().callWall(), r.gex() == null ? null : r.gex().king(),
                    r.gex() == null ? null : r.gex().putWall(), r.passed());
        }
        return sb.toString();
    }

    public static String snapshot(List<SnapshotEntry> entries) {
        StringBuilder sb = new StringBuilder("symbol,core,side,bias,state,zoya,quality_score,quality_grade,price,entry,stop,target1,shares,earnings_date,error\n");
        for (SnapshotEntry e : entries) {
            line(sb, e.symbol(), e.core(), e.side(), e.bias(), e.state(), e.zoya(), e.qualityScore(), e.qualityGrade(),
                    e.price(), e.entry(), e.stop(), e.target1(), e.shares(), e.earningsDate(), e.error());
        }
        return sb.toString();
    }

    public static String bars(String symbol, List<Bar> bars) {
        StringBuilder sb = new StringBuilder("symbol,time_utc,time_ny,session,open,high,low,close,volume\n");
        for (Bar b : bars) {
            line(sb, symbol, b.time(), b.time().atZone(AppConfig.NEW_YORK).toLocalDateTime(), b.session(),
                    b.open(), b.high(), b.low(), b.close(), b.volume());
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, Object... vals) {
        sb.append(java.util.Arrays.stream(vals).map(Csv::cell).collect(Collectors.joining(","))).append('\n');
    }

    private static String cell(Object v) {
        if (v == null) return "";
        String s = v instanceof Instant i ? i.toString() : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
