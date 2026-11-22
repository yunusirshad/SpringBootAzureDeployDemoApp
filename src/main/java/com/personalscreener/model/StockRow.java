package com.personalscreener.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Everything one row on the Screen tab shows. Nullable numbers are "no data". */
public class StockRow {
    public String symbol, exchange, name;
    public boolean userAdded;
    /** SUPER, TOP, CORE, NO_UPTREND, SCREEN_ONLY */
    public String list;
    public int rank;

    // screen
    public boolean screenPass;
    public List<String> screenFails = new ArrayList<>();
    public Double marketCap, targetUpsidePct, debtToEquity, debtToAssets, debtToCapital, cashToDebt, revenueGrowth;
    public String source;

    // compliance
    public String zoya = "NOT_CHECKED";
    public String zoyaReportDate;
    public String zoyaCheckedAt;

    // quality
    public int qualityScore;
    public boolean qualityPass;
    public List<String> qualityReasons = new ArrayList<>();
    public Double atr, atrPct, floatShares, spreadBps, dollarVolume;

    // price
    public Double price, previousClose, changePct, dayHigh, dayLow, dayRange, rangeUsedPct, rangeLeft;
    public Double volume, avgVolume10, avgVolume30, relVol, relVol30;
    public String state;

    // daily trend
    public Double sma50, sma200, smaGapPct;
    public boolean uptrend;
    public Double rsi, macd, macdSignal, adx, plusDi, minusDi, stoch, bandPct;
    public boolean macdUp, adxRising;
    /** Long when the positive direction leads (ADX rising), Short otherwise. */
    public String side;

    // intraday
    public Double ema20h1;
    public boolean above1hEma;
    public Double breakoutLevel, toBreakoutPct;
    public String breakoutSource;
    public int trend15m, trend1h, trend4h;
    public String bias;
    public String trendNote;

    // earnings
    public LocalDate earningsDate;
    public Integer earningsInDays;
    public boolean earningsSoon;

    // options
    public GexLevels gex;

    public String updatedAt;
    public String error;
}
