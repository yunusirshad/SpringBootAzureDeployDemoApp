# Grok prompt: API v1 fixes for the screener

Paste everything below the line into the Grok app builder.

---

Update my screener app. Keep every existing route and field working; only add or fix what is listed. All times are America/New_York. Every number is a JSON number (never a string), rounded to 2 decimals for prices and ATR multiples, 1 decimal for percents. A value that can't be computed is `null` plus a short `*_note` string saying why; never the word "unavailable" in a number field.

## 0. Hosting (I do this myself)
The site must be public. In a private browser window, `GET /api/v1/purple/SRTA` must return JSON, not the "Continue with Grok" sign-in page. If any setting in the app controls this, make every `/api/v1/*` route public and read-only.

## 1. Gamma flip in `GET /api/v1/gex`
For every stock, compute the gamma flip from the option chain you already load for the walls (expiries in the next 45 days):
1. For each contract, Black-Scholes gamma with its implied volatility, time to expiry in years (minimum 1 day), risk-free rate 4%.
2. Dealer gamma exposure at a test spot price S: sum over calls of `gamma(S) * OI * 100 * S^2 * 0.01` minus the same sum over puts.
3. Evaluate it on a grid of S from 0.7× to 1.3× the current price in 0.5% steps. The gamma flip is the S where the total crosses from negative to positive (interpolate linearly between the two grid points). If there are several crossings, take the one nearest the current price.
4. Fields per stock: `gammaFlip` (number or null), `aboveFlip` (true/false/null), `gammaFlip_note` (why null: "no chain", "no crossing in ±30%", "too few contracts").
5. Do not let a missing flip hide a stock: rules that need the flip treat null as "not confirmed", not as "skip".

## 2. Every row of `GET /api/v1/purple` and `GET /api/v1/purple/{ticker}`
Each row must have all of these fields:

```json
{
  "ticker": "AMPL",
  "price": 17.10,
  "atr": 0.87,
  "dayHigh": 17.35, "dayLow": 16.92,
  "dayRange": 0.43, "atrUsedPct": 49.4, "atrLeft": 0.44,
  "trend": "STRONG_UPTREND",
  "sma50": 15.80, "sma200": 13.10, "ema20": 16.70,
  "status": "AT_BUY",
  "buyZone":  { "bottom": 16.76, "top": 17.03, "score": 4 },
  "sellZone": { "bottom": 18.40, "top": 18.62, "score": 3 },
  "buyDistAtr": 0.08, "buyDistPct": 0.4,
  "sellDistAtr": 1.49, "sellDistPct": 7.6,
  "pos": 0.05,
  "closerTo": "BUY",
  "plan": { "entry": 17.03, "stop": 16.67, "target": 18.40, "rr": 3.8, "shares": 500, "positionValue": 8515, "dollarsAtRisk": 180 },
  "skipNote": null,
  "signal": "SETUP FOUND", "signalScore": 78,
  "rvol": 0.51, "rvolPaced": 2.04, "rvolNeed": 2.0,
  "callWall": 20, "putWall": 15, "wallsLockedAt": "2026-11-24T08:00:00-05:00", "thirdFriday": false,
  "gammaFlip": 16.50, "aboveFlip": true,
  "zoyaStatus": "COMPLIANT", "zoyaCheckedAt": "2026-11-24T07:55:00-05:00", "zoyaStale": false,
  "honesty": "Zones are levels where price often trades, not a forecast; the plan only exists at 1:2 or better."
}
```

Rules for the fields:
- **Day range**: `dayRange = dayHigh - dayLow` for today's regular session (pre-market excluded); `atrUsedPct = dayRange / atr * 100` (ATR = 14-day Wilder ATR); `atrLeft = max(0, atr - dayRange)`. Before 9:30 use yesterday's session and say so in `dayRange_note`.
- **Trend**: one of `STRONG_UPTREND`, `EARLY_BULLISH_REVERSAL`, `EARLY_BEARISH_REVERSAL`, `NO_UPTREND`, and always also return `sma50`, `sma200`, `ema20` (daily closes) so I can check it.
- **Zones**: only strong purple zones (3 or more timeframes agree). Buy zone = the strongest zone whose top is below the price (tie: the nearest); sell zone = the strongest zone whose bottom is above the price. `bottom` and `top` are numbers.
- **Distances**: `buyDistAtr = (price - buyZone.top) / atr`, `sellDistAtr = (sellZone.bottom - price) / atr`, `pos = (price - buyZone.top) / (sellZone.bottom - buyZone.top)`; `closerTo` = the zone with the smaller distance.
- **Status**: `AT_BUY` (inside the buy zone, in the lower half of any zone, or within 0.25 ATR above it), `NEAR_BUY` (within 1 ATR or `pos` ≤ 0.33), `AT_SELL` (within 0.25 ATR under the sell zone), `NEAR_SELL` (within 1 ATR or `pos` ≥ 0.67), `INSIDE` (upper half of a zone), `BROKE_SUPPORT` (previous close at or above the zone now above price, and price below it), `MIDDLE`, `NONE`.
- **Plan**: only for `AT_BUY` / `NEAR_BUY` with both zones: entry = buy zone top, stop = buy zone bottom − 0.1 ATR, target = sell zone bottom, `rr = (target - entry) / (entry - stop)`. Only when `rr` ≥ 2. Shares = floor(risk dollars / (entry − stop)), capped so shares × entry ≤ account. Account $9,000, risk 2% = $180. Otherwise `plan` is null and `skipNote` says why (e.g. "R:R 1.4 under 1:2: skip", "no strong zone above for a target", "near resistance: no new long", "closed through the zone below: no long").
- **Signal**: the existing SETUP FOUND / WATCH / PASS with its 0–100 score, on every row.
- **Walls**: call wall and put wall (highest open interest strikes, next 45 days), computed once a day and locked at 8:00 AM ET (`wallsLockedAt`); `thirdFriday` = today is the third Friday of the month (monthly expiry: walls can shift after it).
- **honesty**: the same fixed sentence on every row.

## 3. Day-range in the other routes
Add `dayHigh`, `dayLow`, `dayRange`, `atrUsedPct`, `atrLeft` to every stock row in `/api/v1/screen` (or whatever the main list route is) as well.

## 4. Regime route (keep as is, make "flat" explicit)
`GET /api/v1/regime` keeps, for SPY, QQQ and IWM: `price`, `vwap`, `aboveVwap` (true/false), `trend15m`. `trend15m` is exactly one of `"UP"`, `"DOWN"`, `"FLAT"`; never empty or null while the market is open. FLAT = the 15-minute 20 EMA slope over the last 4 bars is within ±0.05% of price.

## 5. Zoya stale rule
Re-check each ticker on its Zoya stock page every 24 hours. If the last SUCCESSFUL check is more than 7 days old, return `zoyaStatus: "STALE"`, `zoyaStale: true`, keep `zoyaCheckedAt` as that last success date and add `zoyaLastStatus` with what it was. A stale name counts as not compliant: keep it off the Core list and give it no buy signal until a check succeeds.

## 6. Relative volume: no penalty, and a real gate on Buy now
- Quality scoring: RVOL anywhere from 0.5× to 10× earns full points (do not mark down heavy volume).
- `rvol` = today's regular-session volume ÷ the 10-day average daily volume.
- `rvolPaced` = `rvol` ÷ the share of a normal day's volume usually traded by now. Share by time: 9:45 0.10, 10:00 0.16, 10:30 0.25, 11:30 0.38, 12:30 0.48, 1:30 0.57, 2:30 0.67, 3:30 0.80, 4:00 1.00; straight line between those points; before 9:45 use the 9:45 value; outside 9:30–4:00 use `rvol` as is.
- `rvolNeed` = 1.5 from 9:30 to 10:30 ET, 2.0 otherwise.
- **No Buy now / SETUP FOUND entry alert unless `rvolPaced` ≥ `rvolNeed`.** When it is lower, show the row as WATCH with the note "RVOL x.x× under y.y× (paced to the time of day)".

## Done when
- `/api/v1/purple/SRTA` returns JSON in a private window.
- `/api/v1/gex` shows a number for `gammaFlip` on names with a chain, or a `gammaFlip_note` saying why not.
- Every purple row has every field in section 2 (null + note where it truly can't be computed).
- `/api/v1/regime` shows UP, DOWN or FLAT for all three ETFs.
