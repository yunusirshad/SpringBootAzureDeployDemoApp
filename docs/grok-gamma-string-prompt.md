# Grok prompt: "Gamma for TradingView" copy line (largest positive and negative gamma)

Paste everything below the line into the Grok app builder.

---

Add a **"Gamma for TradingView"** line to every stock (in the option-chain panel, under the call wall / King / put wall summary), with a **Copy** button. It produces ONE line of text that I paste into my TradingView indicator's setting "Gamma by strike". The format must be exact, because the indicator parses it.

## Output format (exact)
```
strike=gamma, strike=gamma, strike=gamma
```
- Pairs separated by a comma and a space; strike and value joined by `=`; no other text, no `$`, no thousands separators, no units.
- Strike: plain number as listed on the chain (e.g. `7.5`, `10`, `12.5`; drop trailing zeros).
- Gamma: **net gamma exposure at that strike**, rounded to a whole number (can be negative).
- Sorted by strike, low to high.
- Example: `7.5=-1240, 10=85410, 12.5=3120`

## How to calculate the gamma value per strike
Use the same chain the GEX levels use (all expiries in the next 45 days, up to 8 expiries), and add up all expiries per strike:
- For each contract: t = max(1, days to expiry) / 365; IV = the contract's implied volatility (use 0.50 if missing or ≤ 0.01); S = the current stock price; K = the strike.
- d1 = (ln(S/K) + 0.5 × IV² × t) / (IV × √t)
- gamma = e^(−d1²/2) / √(2π) / (S × IV × √t)
- dollar gamma = gamma × open interest × 100 × S² × 0.01
- **Calls count positive, puts count negative.** Net gamma at a strike = sum of call dollar gamma − sum of put dollar gamma, across all the expiries.
- Divide by 1,000 and round to a whole number (so the numbers stay short); keep the sign.

## Which strikes to include
- Every strike within **±30% of the current price** that has open interest.
- If that gives more than 25 strikes, keep the 25 with the largest absolute net gamma (then sort by strike again).

## On screen
- Show the line in a monospace box under the option chain: `Gamma for TradingView: 7.5=-1240, 10=85410, 12.5=3120` with a **Copy** button that copies only the part after the colon.
- Below it, small, the same four levels the TradingView indicator draws (within 15% of the price):
  - "Largest POSITIVE gamma above: 10 (+85410) · below: 9 (+400)": the largest positive value above and below the price. Positive gamma = dealers long gamma: price tends to slow down, stall or pin there (magnet / ceiling / floor).
  - "Largest NEGATIVE gamma below: 9.2 (−900) · above: 10.5 (−300)": the most negative value below and above the price. Negative gamma = dealers short gamma: price tends to SPEED UP through it (trap door below, squeeze above).
  - Show "—" when there is none on that side.
- Make sure the copy line keeps BOTH positive and negative values (the indicator needs the negatives for the orange lines).
- Add the same line to the API: `GET /api/v1/gamma/{ticker}` returns the line as plain text, and `/api/v1/gex` adds a field `gammaString` per stock.

## BUY NOW / SELL NOW at the gamma lines (same rules as the TradingView indicator)
Check on every closed 5-minute candle of today's session, using the four levels as they stood at the previous candle's close. Fire each signal once per level per day.
- **BUY NOW · floor held**: the candle's low comes within 0.3% of the largest POSITIVE gamma below, and it closes green (close > open) back above that strike.
- **BUY NOW · squeeze**: the candle closes above the largest NEGATIVE gamma above (the previous close was at or below it).
- **SELL NOW · ceiling**: the candle's high comes within 0.3% of the largest POSITIVE gamma above, and it closes red back below that strike.
- **SELL NOW · trap door**: the candle closes below the largest NEGATIVE gamma below (the previous close was at or above it).
- With each signal show: the price, the level, a stop 0.5% beyond the level (below for buys, above for sells), and the target = the next gamma line (positive or negative) beyond the price.
- Label everything from these rules with "GEX" (GEX BUY NOW, GEX SELL NOW, GEX · LARGEST POSITIVE GAMMA, GEX · LARGEST NEGATIVE GAMMA) so it is not confused with other signals.
- Show the latest signal next to the stock ("GEX BUY NOW 9.42 · floor held at +gamma 9.30 · stop 9.25 · target 9.50") and push an alert. These rules come from how dealer gamma tends to act; they are not a backtested edge, so label them "confirm with your own read".

## When it updates
- With the option chain: once a day at 8:00 AM ET (open interest updates overnight). Show "as of" with the date and time it was built.

## Check it
- A stock at 9.42 with call open interest 56 at 10, put open interest 1 at 7.50, and a 10 King: the line contains `10=` with a positive number that is the largest value in the line, and `7.5=` with a small negative number.
- No `$` signs, no `K`/`M` suffixes, no text other than the pairs.
- With the line `8.5=300, 9=400, 9.2=-900, 9.5=120, 10=850, 10.5=-300, 11=1200` and the price at 9.455, the summary reads: positive above 10 (+850), positive below 9 (+400), negative below 9.2 (−900), negative above 10.5 (−300). 11 is more than 15% away, so it is skipped.
