# Grok prompt: fix the purple zones (they don't match the TradingView indicator)

Paste everything below the line into the Grok app builder.

---

The purple zones in my app don't match my TradingView "Purple Zones" indicator, which is the reference. Example: AMPX shows "Sell zone ×3: 6.28–6.48" but the indicator has NO zone of 3 or more timeframes for AMPX. Fix the calculation so it matches the indicator exactly, using the rules below and the test cases at the end.

## What to fix
1. **Only real overlaps count.** A zone's score is the number of the five profile zones whose range actually contains the slice (bottom ≤ midpoint ≤ top). No "nearby", "within x%" or "within x ATR" tolerance, no rounding the edges before comparing, no counting the same profile twice.
2. **Exactly these five profiles, completed data only**, nothing else:
   - yesterday, 5 days, 20 days: 5-minute bars, regular session 9:30–16:00 only (no pre-market or after-hours), last 1 / 5 / 20 completed sessions;
   - 3 months, 1 year: daily bars, last 63 / 252 completed days.
   - While the market is open, today's bars are NOT included in any profile. So the zones do not change during the day; only the price and the distances do.
3. **Each profile's zone**: 120 equal bins from the window's lowest low to highest high; each bar's volume spread evenly across the bins its high–low touches (zero volume counts as 1); start at the bin with the most volume and add one bin at a time, always the heavier neighbour (ties go up), until the band holds 20% of the profile's volume.
4. **Strong = 3 or more.** If no slice is covered by 3 or more profile zones, the stock has NO purple zone: status `NONE`, buy zone None, sell zone None, no plan, note "No zone where 3 or more timeframes agree". Do not fall back to a weaker zone.
5. **Distances use one price.** `buyDistAtr`, `buyDistPct`, `sellDistAtr`, `sellDistPct` must all use the same current price and the same ATR (14-day Wilder ATR from completed daily bars). Right now the % and the ATR distance disagree (e.g. ZTO: 3.46 ATR but 7.4%, which implies two different prices).
6. **Show your work.** Add a collapsible "Profiles" section under Details on each row with the five profile zones (name, bottom, top) and which ones overlap. If a profile is missing (not enough data), say so instead of silently dropping it. Also return them in the API as `profiles: [{name, bottom, top}]`.

## Merging overlaps into a zone
Sort the 10 edges; for each slice between neighbouring edges count how many profile zones contain its midpoint. Neighbouring slices with a count of 2 or more merge into one zone: `band` = the whole merged range, `score` = the highest count in it, `core` = the slices with that highest count. A zone is strong only if `score` ≥ 3.

## Test cases (reference values from the TradingView indicator, Tue Nov 24 2026, during the session)
Your five profile zones must match these within 1–2 cents. If they don't, the profile calculation is wrong; fix that before anything else.

**AMPX** (price about 5.72, ATR 0.76)
| Profile | Zone |
|---|---|
| yesterday | 6.14 – 6.19 |
| 5 days | 6.70 – 6.82 |
| 20 days | 8.59 – 8.95 |
| 3 months | 9.39 – 9.76 |
| 1 year | 9.11 – 10.50 |

Only 3 months and 1 year overlap (9.39–9.76, ×2). Expected: **status NONE, no buy zone, no sell zone, no plan.** Your app currently shows NEAR_SELL with a ×3 zone at 6.28–6.48: wrong.

**ZTO** (price about 19.07, ATR 0.40)
| Profile | Zone |
|---|---|
| yesterday | 20.38 – 20.40 |
| 5 days | 20.38 – 20.43 |
| 20 days | 20.29 – 20.41 |
| 3 months | 19.61 – 19.93 |
| 1 year | 20.34 – 21.32 |

Expected: one strong zone, **×4** (yesterday, 5 days, 20 days, 1 year), **band 20.34–20.43, core 20.38–20.40**. It is above the price, so it is the sell zone: about 3.2 ATR / 6.7% above at 19.07. The 3-month zone (19.61–19.93) stands alone, so it is not a zone. Your app shows ×3 20.40–20.49: the score and the edges are off.

**NOK**: expected ×3, core 7.58–7.60. Your app's 7.55–7.60 (core 7.58–7.59) matches. Keep it.

## Done when
AMPX shows NONE; ZTO shows the ×4 sell zone 20.34–20.43 (core 20.38–20.40); NOK is unchanged; every row's "Profiles" section lists the five profile zones; and for every row `sellDistPct` = (sellZone.bottom / price − 1) × 100 with the same price used for `sellDistAtr`.
