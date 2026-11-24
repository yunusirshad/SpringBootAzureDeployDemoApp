# Grok prompt: purple zone rules

Paste everything below the line into the Grok app builder.

---

Build the purple zone feature for my screener exactly by these rules. Long only. All times America/New_York. Use only completed data: never include today's bars while the market is open.

## 1. Data needed per stock
- 5-minute bars, regular session only (9:30–16:00), for at least the last 25 trading days.
- Daily bars for at least the last 260 trading days.
- Load BOTH datasets for every stock regardless of what chart timeframe the user is viewing. (Bug to avoid: on intraday views the daily data was silently missing, so only 3 of the 5 profiles existed and the result was "None". If either dataset is missing, say which in a `note`; never show "None" because of missing data.)

## 2. Five volume profiles
| Name | Bars | Window |
|---|---|---|
| yesterday | 5-minute | the last completed session |
| 5 days | 5-minute | the last 5 completed sessions |
| 20 days | 5-minute | the last 20 completed sessions |
| 3 months | daily | the last 63 completed daily bars |
| 1 year | daily | the last 252 completed daily bars |

"Completed": while the market is open, today's bars are excluded; after 4:00 PM (and on weekends/holidays) the latest session counts as yesterday. A profile needs at least 3 bars, otherwise it is skipped.

## 3. Each profile's zone
1. Price range = lowest low to highest high of the window. Split it into 120 equal bins.
2. Each bar's volume is spread evenly across every bin its high–low range touches (a bar with zero volume counts as 1).
3. POC = the bin with the most volume.
4. Grow a band from the POC one bin at a time, always adding the heavier neighbour (ties go up), until the band holds 20% of the profile's total volume.
5. The profile's zone = bottom of the lowest bin to top of the highest bin in the band.

## 4. Confluence (where timeframes agree)
1. Collect all 10 edges (bottom and top of the 5 zones), sort them.
2. For every slice between two neighbouring edges, count how many zones cover its midpoint.
3. Slices covered by 2 or more zones are candidates; touching candidate slices merge into one zone.
4. A merged zone's `score` = the highest count of any of its slices (2 to 5). Its `core` = the slices with that highest count; its `band` = the whole merged range; `members` = the timeframes involved.

## 5. Strong zones and the single purple zone
- Strong = score 3 or more (setting "Minimum timeframes", default 3, allowed 2–5).
- The one purple zone shown on the chart = the strong zone with the highest score; ties go to the one nearest the price. If no zone scores 3 or more, show "None: fewer than 3 timeframes agree".

## 6. Buy zone / sell zone reading (for each stock row)
Using the strong zones (score ≥ 3) and ATR = 14-day Wilder ATR:
- **Current zone**: a strong zone the price is inside (highest score wins).
- **Buy zone (support)**: the strongest zone whose top is below the price (or below the current zone); ties go to the nearest.
- **Sell zone (resistance)**: the strongest zone whose bottom is above the price (or above the current zone); ties go to the nearest.
- `buyDistAtr = (price − buyZone.top) / ATR`, `buyDistPct = (price / buyZone.top − 1) × 100`
- `sellDistAtr = (sellZone.bottom − price) / ATR`, `sellDistPct = (sellZone.bottom / price − 1) × 100`
- `pos = (price − buyZone.top) / (sellZone.bottom − buyZone.top)` (0 = at the buy zone, 1 = at the sell zone)
- `closerTo` = BUY or SELL, whichever distance is smaller.

**Status**, checked in this order:
1. `BROKE_SUPPORT`: the previous close was at or above the zone now above the price, and price is below it.
2. `INSIDE`: the price is inside a strong zone. If it is in the LOWER half of that zone, it becomes `AT_BUY` (that zone is the buy zone, distance 0).
3. `NONE`: no strong zone above or below.
4. `AT_BUY`: within 0.25 ATR above the buy zone.
5. `AT_SELL`: within 0.25 ATR under the sell zone.
6. `NEAR_BUY`: within 1 ATR of the buy zone, or `pos` ≤ 0.33.
7. `NEAR_SELL`: within 1 ATR of the sell zone, or `pos` ≥ 0.67.
8. `MIDDLE`: anything else.

## 7. Trade plan (only at AT_BUY or NEAR_BUY)
- Entry = buy zone top. Stop = buy zone bottom − 0.1 × ATR. Target = sell zone bottom.
- `rr = (target − entry) / (entry − stop)`. Show the plan only when `rr` ≥ 2.
- Shares = floor(risk dollars / (entry − stop)), capped so shares × entry ≤ account. Account $9,000, risk 2% = $180.
- No plan → a short skip note: "R:R x.x under the 1:2 minimum: skip", "No strong zone above for a target", "Near resistance: take profit or tighten the stop; no new long" (NEAR_SELL / AT_SELL), "Closed through the zone below: it failed as support; no long" (BROKE_SUPPORT), "Inside a zone: wait for a close above it (support) or below it (resistance)" (INSIDE), "Between the zones: no edge from them" (MIDDLE).

## 8. Sort
Sort the Core list by status: AT_BUY, NEAR_BUY, MIDDLE, INSIDE, NEAR_SELL, AT_SELL, BROKE_SUPPORT, NONE; within the same status, by `buyDistAtr` ascending. The super stock stays pinned on top.

## 9. Display per row
Status badge; "Buy zone ×score: bottom–top (core coreBottom–coreTop) · x.xx ATR / y.y% below"; "Sell zone ×score: … above"; "Closer to: buy zone / sell zone (pos 0.xx)"; the plan line (entry, stop, target, R:R, shares, position value, $ at risk) or the skip note. Honesty line: "Zones are levels where price often trades and reacts, not targets price must reach; the plan only exists at 1:2 or better."

## 10. Check it with this example
Price 17.10, ATR 0.87, buy zone 16.76–17.03 (×4), sell zone 18.40–18.62 (×3) → `AT_BUY` (0.08 ATR above the zone), closer to BUY, pos 0.05; plan entry 17.03, stop 16.67, target 18.40, R:R 3.8, 504 shares ($8,583, $180 at risk).
