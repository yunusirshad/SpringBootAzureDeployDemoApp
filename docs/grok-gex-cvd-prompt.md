# Grok prompt: "Who won the wall" column (GEX levels + CVD)

Paste everything below the line into the Grok app builder.

---

Add a column **"Who won the wall"** to the GEX levels table (and the same fields to `/api/v1/gex`). It answers: at the call wall or put wall, are buyers or sellers winning, and is the wall being defended? Use the call wall and put wall the app already has. All times America/New_York; regular session 9:30–16:00 only.

## The idea
- GEX tells you where to look: the **call wall** is usually the ceiling, the **put wall** usually the floor. But a wall only holds if someone defends it.
- CVD tells you who is winning: **CVD = market buys − market sells**, added up through the day.
  - CVD up, price up = buyers win. CVD down, price down = sellers win.
  - CVD up, price stuck = buyers are being absorbed. CVD down, price holding = sellers are being absorbed.

## 1. CVD per stock
- Use today's **1-minute bars** (regular session). If you have real trade-side data (trades at the ask = buys, at the bid = sells), use it. Otherwise estimate: a 1-minute bar's volume counts as **buying if close > open, selling if close < open, nothing if equal**.
- `delta` per minute = buy volume − sell volume. `cvd` = running sum of delta since 9:30, reset every day.
- Refresh every 1–5 minutes during market hours.

## 2. The fight over the last 30 minutes (one rolling window)
- `flowPct` = sum(delta) / sum(volume) over the last 30 one-minute bars × 100 (from −100 to +100).
- `priceChgPct` = (last price / price 30 minutes ago − 1) × 100.
- CVD **climbing** if `flowPct ≥ +20`; **dumping** if `flowPct ≤ −20`; otherwise flat.
- Price **stuck** if |priceChgPct| < 0.3; price **up** if ≥ +0.6; price **down** if ≤ −0.6.
- `fight`: BUYERS WIN (climbing + up), SELLERS WIN (dumping + down), BUYERS ABSORBED (climbing but priceChgPct < 0.3), SELLERS ABSORBED (dumping but priceChgPct > −0.3), otherwise NO CLEAR FIGHT.

## 3. At the walls
- **At the put wall**: the price is within 1% of the put wall, or the last 30 minutes' low touched within 1% of it and the price is still no more than 1% below it.
- **At the call wall**: the mirror (within 1%, or the 30-minute high touched within 1% and price no more than 1% above).

## 4. The column value (check in this order)
| Value | Rule | Colour | Meaning |
|---|---|---|---|
| **A SETUP — floor defended** | at the put wall, CVD dumping, priceChgPct > −0.3 and price not more than 0.5% below the put wall | green | sellers are being absorbed at the floor: the best long setup |
| **FLOOR NOT DEFENDED** | at the put wall, CVD dumping and price down (≤ −0.6) | red | nobody is defending, don't catch it |
| **TRAP — stay out** | at the put wall, CVD climbing and price stuck | amber | buyers are being absorbed: trap |
| **CALL WALL BREAKING** | at the call wall, CVD climbing, price up and price above the call wall | green | buyers win, the ceiling is giving way |
| **CEILING HOLDS** | at the call wall, CVD climbing, priceChgPct < 0.3 and price not more than 0.5% above the call wall | amber | buyers absorbed at the ceiling: no long here, take profit |
| **AT PUT WALL — watch** / **AT CALL WALL — watch** | at a wall, none of the above | grey | wait for the CVD read |
| **BUYERS ABSORBED — trap** | away from the walls, CVD climbing and price stuck | amber | stay out |
| **Buyers win** / **Sellers win** | away from the walls | light green / light red | context only |
| **Away from the walls** | anything else | grey | nothing to do |

Show under the value, small: "CVD 30m: +34% net buying · price 30m: +0.12% · put wall 9.00 (−0.4%) · call wall 10.00 (+5.6%)".
Outside market hours show the last value from the close, labelled "(at the close)".

## 5. Signals and alerts
- **A SETUP — floor defended** on a Core stock that is otherwise Buy now / Ready to buy: upgrade the note to "A setup at the put wall" and push an alert once per stock per day.
- **FLOOR NOT DEFENDED**: never show Buy now for that stock until it reclaims the put wall.
- **CEILING HOLDS** on a stock you hold: note "take profit / tighten the stop at the call wall".
- **TRAP**: block Buy now while it lasts.

## 6. API fields (`/api/v1/gex`, per stock)
`wallFight` (the value), `fight`, `flowPct30m`, `priceChgPct30m`, `cvdToday`, `atPutWall`, `atCallWall`, `putWallDistPct`, `callWallDistPct`, `cvdSource` ("trade side" or "estimated from 1-minute bars"), `asOf`.

## 7. Check it
- A stock 0.3% above its put wall, flowPct −35, priceChgPct −0.1 → **A SETUP — floor defended**.
- Same stock, priceChgPct −0.9 → **FLOOR NOT DEFENDED**.
- A stock 0.2% under its call wall, flowPct +30, priceChgPct +0.1 → **CEILING HOLDS**.
- A stock 0.4% above its call wall, flowPct +30, priceChgPct +0.8 → **CALL WALL BREAKING**.
- A stock 5% from both walls, flowPct +25, priceChgPct +0.05 → **BUYERS ABSORBED — trap**.

Note for the screen: the CVD here is an estimate from 1-minute candles unless real trade-side data is available; say which one in `cvdSource`.
