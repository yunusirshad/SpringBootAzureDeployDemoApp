package com.personalscreener.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Everything one row on the Screen tab shows. Nullable numbers are "no data". */
public class StockRow {
    public String symbol, exchange, name;
    public boolean userAdded;
    /** CORE, NO_UPTREND, SCREEN_ONLY */
    public String list;
    public int rank;
    /** 0-100, how good a long it is; the Core list is sorted by it. */
    public int longScore;
    public List<String> longScoreParts = new ArrayList<>();

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
    /** STRONG_UPTREND, EARLY_BULLISH_REVERSAL, EARLY_BEARISH_REVERSAL or NO_UPTREND, from completed daily candles. */
    public String trendClass = "NO_UPTREND";
    public String trendClassWhy;
    public Double ema20d;
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

    // session phase (latest pre-market or New York session, from the Sessions indicator)
    public String sessionName, sessionDate, sessionPhase, sessionScores;
    public Integer sessionPct;
    public boolean sessionLive;
    /** 1 Bull Distribution, 2 Bull Absorption, 3 Bear Absorption, 4 Bull Manipulation, 5 Bull Accumulation, 9 anything else. */
    public int sessionGroup = 9;

    // quantum shift (1h)
    public Double qsEnergy;
    public int qsRegime;
    public String qsLastSignal;

    // earnings
    public LocalDate earningsDate;
    public Integer earningsInDays;
    public boolean earningsSoon;

    // options
    public GexLevels gex;

    public String updatedAt;
    public String error;
}
