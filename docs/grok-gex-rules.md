# Grok prompt: GEX levels rules

Paste everything below the line into the Grok app builder.

---

Build the GEX levels feature for my screener exactly by these rules. All times America/New_York. GEX levels are where options positioning sits; they are levels to plan around, not a forecast.

## 1. When and what to load
- Read every listed stock's option chain **once a day at 8:00 AM ET, Monday–Friday** (after the overnight open-interest update). Lock the levels for the day and show `wallsLockedAt` (the time they were read). A stock that is new to the list gets its chain when it first appears; a stock with no listed options is tried once a day, not on every refresh.
- Source: Cboe delayed quotes first; if Cboe doesn't answer, Yahoo Finance options. Show which source was used.
- Window: every expiry in the **next 45 days**, at most 8 expiries.
- Per contract you need: strike, call or put, open interest (OI), implied volatility (IV), expiry.
- Spot = the stock's latest price when the chain is read.

## 2. Sum by strike across all expiries in the window
Add up OI by strike over ALL the expiries (so a big monthly position is not hidden by a quiet weekly):
- `callOi(strike)` = sum of call OI at that strike
- `putOi(strike)` = sum of put OI at that strike
- `totalOi` = all call OI + all put OI in the window

## 3. Gamma per contract (for King and net GEX)
- t = max(1, days to expiry) / 365
- IV: the contract's IV; if missing or ≤ 0.01, use 0.50
- d1 = (ln(S / K) + 0.5 × IV² × t) / (IV × √t)
- gamma = e^(−d1²/2) / √(2π) / (S × IV × √t)
- Dollar gamma for a 1% move = gamma × OI × 100 × S² × 0.01
- Calls count **positive**, puts **negative**. `netGex(strike)` = calls − puts at that strike, summed over the expiries.

## 4. The levels
- **Call wall** = the strike with the most call OI. Tie: the strike nearer the spot.
- **Put wall** = the strike with the most put OI. Tie: the strike nearer the spot.
- **King** = the strike with the largest absolute `netGex`. Tie: the strike nearer the spot.
- **Expiry** = the single expiry date that holds the most OI (calls + puts) in the window.
- For each level also show its OI and its **share of totalOi** in %: call wall % = callOi(callWall) / totalOi × 100; put wall % likewise; King % = (callOi + putOi at King) / totalOi × 100.
- Totals: `callOi`, `putOi`, `callPutRatio` = callOi / putOi, `netGex` = sum of netGex over all strikes (positive = dealers long gamma, moves tend to be damped; negative = moves tend to be amplified).
- **Gamma flip** (new): evaluate total netGex (all strikes) at test spot prices from 0.7× to 1.3× the spot in 0.5% steps (recompute gamma at each test price). The flip is where the total crosses from negative to positive, interpolated between the two grid points; if several, the one nearest the spot. If there is no crossing, `gammaFlip` = null with the note "no crossing within ±30%". Never let a missing flip remove a stock.
- **Third Friday flag**: `thirdFriday` = today is the third Friday of the month (monthly expiry; walls often move after it).
- No chain / zero OI → all levels null with a note ("no listed options" or "could not read the chain"), and the row still shows.

## 5. How the levels are used in the signals
- **Target**: the call wall is the upside target and resistance. Price at or above the call wall → **Sell now** ("at the call wall (target)").
- **Support / stop**: the put wall, when it is below the expected buy, raises the stop's support level (the stop is the higher of today's low or the put wall, and at least 0.25 ATR under the buy).
- **Expected target** for a Wait row: buy + 1 ATR, capped at the call wall when the call wall is above the buy.
- **Long score (10 points)**: 0 if the price is at or under the put wall or at or above the call wall; 10 if the call wall is at least 1 ATR above the price; 5 if it is closer than 1 ATR.
- **Trade plan targets**: the call wall is one of the resistance levels a plan target may stop at.

## 6. GEX table (one row per Core stock)
Columns: Exchange, Stock (name, trend tile colour), Zoya, Price, Range meter, Current state, From yesterday %, **Call wall** (strike, OI, %), **King** (strike, %), **Put wall** (strike, OI, %), **Expiry**, **Gamma flip**, **Bias**.
- **Bias** = Bullish / Bearish / Mixed from three timeframes: 15-minute, 1-hour and 4-hour completed candles. Each timeframe is +1 when the last close is above its 20 EMA and the EMA is not lower than 3 candles ago; −1 when the close is below the 20 EMA and the EMA is not higher than 3 candles ago; otherwise 0. Sum > 0 → Bullish, < 0 → Bearish, 0 → Mixed. Show the three arrows (15m ↑ · 1h ↑ · 4h ↑).
- Filters above the table: **State** (Any / Bull / Bear, matching the start of "Current state") and **Bias** (Any / Bullish / Bearish / Mixed).
- Under the title: "From each stock's option chain, read once a day at 8:00 AM ET. Open interest is summed by strike over the expiries in the next 45 days. Call wall and put wall are the strikes with the most call and put open interest; King is the strike with the largest net gamma. It is not a forecast."

## 7. Option chain view (click a stock)
Strikes within 40% of the price; per strike: call OI, put OI, OI-weighted call IV and put IV, net GEX; tag the Call wall, Put wall and King strikes; a summary line "Call wall X (OI) · King Y · Put wall Z (OI) · calls N / puts M · C/P r". Allow picking a single expiry or all expiries in the window.

## 8. API fields (`/api/v1/gex`, per stock)
`ticker, spot, source, wallsLockedAt, expiry, expiries, lastExpiry, callWall, callWallOi, callWallPct, putWall, putWallOi, putWallPct, king, kingPct, totalOi, callOi, putOi, callPutRatio, netGex, gammaFlip, aboveFlip, gammaFlip_note, thirdFriday, note`. Numbers as JSON numbers; null + note when missing.
