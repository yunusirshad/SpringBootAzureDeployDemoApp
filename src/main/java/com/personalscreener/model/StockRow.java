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
    /** For a name you added that is not in Core: why (not compliant, no uptrend). */
    public String offReason;
    public int rank;
    /** 0-100, how good a long it is; the Core list is sorted by it. */
    public int longScore;
    public List<String> longScoreParts = new ArrayList<>();
    /** 0-100, low risk and high reward; the Core list is sorted by it. */
    public int setupScore;
    public List<String> setupParts = new ArrayList<>();
    /** Reward to risk from support (today's low or the put wall) to target (low + 1 ATR, or the call wall). */
    public Double rewardRisk, setupStop, setupTarget, riskPct, rewardPct;
    /** The best Buy now (or Ready to buy) name, always first in Core. */
    public boolean superStock;
    /** BUY_NOW, READY, HOLD, READY_SELL, SELL_NOW or WAIT. */
    public String signal = "WAIT";
    /** For a Wait: expected buy (the breakout level), stop and target. */
    public Double expBuy, expStop, expTarget;
    /** Why: the sell reasons, what a Buy now still needs, or why it is Hold or Wait. */
    public List<String> signalNotes = new ArrayList<>();

    // screen
    public boolean screenPass;
    public List<String> screenFails = new ArrayList<>();
    public Double marketCap, targetUpsidePct, debtToEquity, debtToAssets, debtToCapital, cashToDebt, revenueGrowth;
    public String source;

    // compliance
    public String zoya = "NOT_CHECKED";
    public String zoyaReportDate;
    public String zoyaCheckedAt;
    /** True when the last successful Zoya check is over the stale limit (7 days); zoya is then "STALE". */
    public boolean zoyaStale;
    /** The status from that last check, kept for display when stale. */
    public String zoyaLastStatus;

    // quality
    public int qualityScore;
    public boolean qualityPass;
    public List<String> qualityReasons = new ArrayList<>();
    public Double atr, atrPct, floatShares, spreadBps, dollarVolume;

    // price
    public Double price, previousClose, changePct, dayHigh, dayLow, dayRange, rangeUsedPct, rangeLeft;
    public Double volume, avgVolume10, avgVolume30, relVol, relVol30;
    /** Paced RVOL: today's volume against the usual volume by this time of day (2.0 = twice the normal pace). */
    public Double rvolPaced;
    /** The paced RVOL Buy now needs right now: 1.5 in the first hour, 2.0 after. */
    public Double rvolNeed;
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
    /** Long when the positive direction leads (+DI over -DI), Flat otherwise. Long only: there is no short side. */
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

    // purple zones (multi-timeframe magnet zones) and where price sits between them
    public java.util.List<com.personalscreener.analysis.MagnetZones.Zone> purpleZones = new java.util.ArrayList<>();
    public com.personalscreener.analysis.PurpleZone.Reading purple;

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
