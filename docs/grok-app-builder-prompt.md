# Grok app builder prompt: Personal Screener 2026

Copy everything below the line into the Grok app builder. If it stops part-way or shortens things, paste one "Stage" from section 0 at a time and say "continue with the next stage".

---

Build a complete, working web app called **Personal Screener 2026**: a Shariah-compliant, **long-only** US stock screener and trading assistant for Nasdaq and NYSE small caps. It screens stocks, checks Zoya compliance, ranks the best low-risk / high-reward longs, gives Buy/Sell signals, reads option chains for GEX levels, scores trading sessions, runs a PV Confirm mean-reversion system and a top-down trading-plan bot, builds a per-stock playbook from backtests, calls the market bias every morning and hourly, and sends alerts. Follow every rule and number below exactly. Where a rule leaves something open, choose a sensible default and note it in a code comment. Nothing in the app places orders, and it is not financial advice.

## 0. Platform requirements and build order

**The app must have a backend.** It cannot be browser-only:
- The browser cannot call Yahoo Finance, TradingView, Zoya, Cboe or SEC directly (CORS). Every data request goes through the app's own backend (server routes or serverless functions), which returns JSON to the page.
- Several jobs must run on a schedule in New York time, even with no browser open: the screen every 15 minutes, option chains at 8:00 AM, the market bias at 9:30 AM and hourly, the plan bot every 15 minutes, and PV Confirm at 4:15 PM. Use the platform's scheduled jobs / cron (or an always-on server process).
- State must persist across restarts: use the platform's database or key-value store (or JSON files on a persistent disk).
- Secrets (Telegram token, ntfy topic, Zoya API key) live in environment variables or the platform's secret store, never in code.

Use any stack the builder supports well (for example TypeScript/Node with serverless functions and a scheduled-job feature, or Python). If it can run Java, Spring Boot 3 with Java 21 also works.

**Build in stages,** each working before the next:
1. Data layer (section 2), the market calendar, the screen and Zoya (sections 3-4), per-stock analysis and trend classes (sections 5-6), the Core list and signals (section 7), the Screen tab.
2. Option chains and GEX, with the inline Chain panel (section 8); the Sessions tab (section 9).
3. PV Confirm (section 10) and the market bias (section 11).
4. The trading plan bot (section 12), the Playbook and profile (section 13), alerts (section 14).
5. The other tabs (section 15), the header (section 16), settings, the API list and tests (sections 17-19).

## 1. Project basics

- **Frontend:** one page with tabs (Screen, Alerts, Analyze, Technicals, Backtest, Sessions, APP Details). Plain JavaScript/TypeScript or a light framework. Light and dark themes via CSS variables. Works at phone width (390 px) with no sideways page scroll.
- **Storage** (database tables or JSON documents): watchlist, price alerts, Zoya cache, GEX cache, last screen snapshot, PV settings, PV open trades, PV alerts, market-bias history, Buy/Sell alerts, plan-bot settings, plan-bot open trades, plan-bot alerts, plan-bot backtest, trader profile.
- **Time:** everything in America/New_York. Include a market calendar with NYSE holidays for 2026 and 2027 and the 1:00 PM early closes (2026-11-27, 2026-12-24, 2027-11-26).
- **Static files** are served with `Cache-Control: no-cache`, so a browser never runs an old script against a new page.
- **Errors:** every backend error is logged with its stack trace and returned as JSON `{ "error": "…" }`; the page shows the message instead of failing silently.
- **Tests:** unit tests for every calculation below (indicators, scores, classifiers, parsers, signal rules, trade replay, sizing, schedules).

## 2. Data sources

| Source | Used for | Notes |
|---|---|---|
| TradingView scanner (`POST https://scanner.tradingview.com/america/scan`) | The screen's fundamentals, float, earnings date, exchange breadth (advancers/decliners) | If unreachable, screen a fallback list from settings and mark those rows "Fundamentals not verified (TradingView unreachable)" while still letting them through |
| Yahoo Finance chart v8 (`query1.finance.yahoo.com/v8/finance/chart/{sym}`) | All candles (daily, 1h, 15m, 5m, with pre/post-market), quotes from chart meta | Browser-like User-Agent; no login needed |
| Yahoo crumb session (`fc.yahoo.com` cookie, then a `finance.yahoo.com` page as second source; `/v1/test/getcrumb`) | v7 batch quotes (bid/ask), v7 options (fallback), quoteSummary earnings dates | Switchable (`SCREENER_YAHOO_CRUMB`). Log the reason when it fails, retry after 5 minutes, renew a stale crumb once on 401/403 |
| Cboe delayed quotes (`https://cdn.cboe.com/api/global/delayed_quotes/options/{SYM}.json`) | Option chains (primary) | One file with every expiry; contracts in OCC form `ROOT YYMMDD C/P STRIKE×1000 (8 digits)` with `open_interest`, `iv`; spot in `data.current_price`. A 404 means no options |
| Zoya stock page (`https://zoya.finance/stocks/{ticker-lowercase}`) | Shariah compliance | See section 4 |
| SEC EDGAR company facts | Reported results on the Analyze tab | Needs a User-Agent with a contact (`SCREENER_SEC_USER_AGENT`) |

## 3. The screen (refresh schedule and filters)

- **Full screen:** every **15 minutes on trading days from 7:00 AM to 4:00 PM ET** (7:00, 7:15, … 4:00), and once at startup. Weekends and holidays are skipped.
- **Price tick:** every 10 seconds during the extended session (4:00 AM to 8:00 PM ET), every 5 minutes otherwise. Each tick re-ranks the lists.
- **Filters** (TradingView, then checked again in code):
  - Exchanges NASDAQ and NYSE, common stocks.
  - Price $1.50 to $20.
  - Market cap over $300M.
  - Target upside over 25%.
  - Debt/equity under 0.5.
  - Debt/assets under 0.3.
  - Debt/capital under 0.3.
  - Cash/debt over 1.
  - Revenue growth over 5%.
- **Limits:** analyse at most 60 names.
- **Watchlist names:** always screened, whether or not they pass. The watchlist supports add (one or bulk "SRTA, AUR, EVLV"), remove, import from a .txt/.csv file, and export.
- **Fallback list** (used only when TradingView is unreachable): IOVA, AMPL, FRSH, DCTH, BB, FENC, RELY, PATH, MITK, DSP, OMDA, ANGO, ADMA, KOPN, MTUS, INNV.

## 4. Zoya compliance

- For every screened name, read its Zoya page. The status line on the page reads like `MITK stock is Shariah-compliant`, `JPM stock is not Shariah-compliant`, or `… Questionable`. Strip the HTML, replace `&nbsp;`, and match `<TICKER> stock is (…)` (fall back to the summary line `(<TICKER>) is …`). Read the "As of <Month d, yyyy>" date.
- Statuses: COMPLIANT, NON_COMPLIANT, QUESTIONABLE, NOT_COVERED (HTTP 404), NOT_CHECKED (lookup failed).
- **Cache each result for 24 hours** in a JSON file so a ticker is looked up at most once a day, even across restarts. Concurrent requests for the same ticker share one lookup. A failed lookup is not cached and is retried after 15 minutes.
- Optional alternative: Zoya's GraphQL API with `ZOYA_SOURCE=api` and `ZOYA_API_KEY`.
- **Only COMPLIANT names are ever displayed** (Core, GEX table, PV Confirm). A name the user added that is not compliant shows under the Core table with the reason ("Not Shariah-compliant on Zoya, so it is not listed", "Zoya does not cover it…").
- The Zoya badge links to the stock's Zoya page.
- Endpoints: `GET /api/zoya/{ticker}` (`?refresh=true` forces a lookup), `GET /api/zoya` (the cache).

## 5. Per-stock analysis

All indicators use **completed candles only** (drop the forming candle).

- **Daily (1 year):**
  - SMA50, SMA200 and their gap %.
  - Wilder RSI(14).
  - MACD 12/26/9; "up" when the line is above the signal.
  - Wilder ADX(14) with +DI/−DI; "rising" when +DI > −DI.
  - Stochastic 14/3.
  - Bollinger position: % between the 20-day 2σ bands.
  - Wilder ATR(14) and ATR %.
  - 10- and 30-day average volume.
  - Daily 20 EMA.
- **Side:** **Long** when +DI > −DI, otherwise **Flat**. There is **no short side anywhere** in the app.
- **Uptrend check:** SMA50 > SMA200.
- **Intraday bias:** 15m (10 days), 1h (3 months) and 4h (built from 1h, the current block excluded while it is forming). On each timeframe, trend = +1 when the close is above a rising 20 EMA (EMA now ≥ EMA 3 bars ago), −1 when it is below a falling one, else 0. Bias = Bullish if the sum is > 0, Bearish if < 0, else Mixed. Also record above/below the 1h 20 EMA.
- **Quote fields:** price, previous close, change %, day high/low, day range.
  - Range meter: day range ÷ ATR, as "% used" and "left".
  - Relative volume vs the 10-day and 30-day averages.
  - Spread in bps from bid/ask when available.
  - Breakout level = the higher of today's pre-market high and the prior day's high, with "to breakout %".
- **Quality score (0–100):** five clips worth 20 points each.
  - The clips: relative volume 0.5–2×, dollar volume $0.5M–$20M, spread 5–80 bps, float 2M–50M shares, ATR% 3–12%.
  - A value inside its clip scores 20. Outside, it scores `20 × (1 − log10 distance)`, reaching 0 at 10× outside.
  - A missing spread scores 10; a missing float scores 0.
  - **Pass** needs a score ≥ 60, dollar volume ≥ $2M, a spread ≤ 50 bps (when known) and a known float.
- **Current state** (from today's move):
  - **Initiation:** the move is ≥ 0.6 ATR on ≥ 1.3× relative volume.
  - **Accumulation (up) or distribution (down):** the move is ≥ 0.45 ATR, or relative volume ≥ 1× with the close in the third of the range that agrees with the move. Up days with ≥ 1× volume also count.
  - **Manipulation:** anything else.
  - The label is prefixed Bull or Bear by direction.
- **Earnings:** the next date (TradingView or Yahoo). "Earnings soon" means within 3 trading days.

## 6. Trend classification (each row is colour-coded)

From completed daily closes, using the 20 EMA, SMA50 and SMA200 only. The indicators are readings, not inputs here.

- **Strong Uptrend (green):** SMA50 > SMA200, the close above SMA50, and not below a falling 20 EMA.
- **Early Bullish Reversal (teal):** either SMA50 crossed above SMA200 within the last 15 sessions with the close above the 20 EMA, or SMA50 ≤ SMA200 but the close is above SMA50 and above a rising 20 EMA (rising = higher than 5 bars ago).
- **Early Bearish Reversal (red):** SMA50 > SMA200, but the close is below SMA50, or below a falling 20 EMA.
- **No Uptrend (grey):** everything else. **Fewer than 200 daily candles always means No Uptrend.**

The whole row takes the class colour, with a coloured left edge and a badge. RSI, MACD, ADX (with ↑/↓), Stoch and Band show on every row as readings.

## 7. The Core list (one list, no Top 10, no cap)

**Core** contains every screened or user-added name that is **Zoya COMPLIANT** and a **Strong Uptrend or Early Bullish Reversal**.

### 7.1 Long score (0–100)

| Part | Points |
|---|---|
| Trend class | Strong Uptrend 35, Early Bullish Reversal 25, Early Bearish Reversal 5 |
| 15m/1h/4h bias | Bullish 10, Mixed 5 |
| Above the 1h 20 EMA | 5 |
| +DI over −DI | 10 |
| MACD up | 5 |
| RSI | 50–70: 5; 40–50 or 70–80: 2 |
| Quality | quality score ÷ 10 |
| Relative volume ≥ 1× | 5 |
| Zoya compliant | 5 |
| Room to the call wall | 10 if price is above the put wall and at least 1 ATR under the call wall; 5 if under the call wall by less |
| Earnings within 3 trading days | −15 |

### 7.2 Setup score (0–100): low risk, high reward

- **Range meter (25):** `25 × (1 − used% / 100)`, so 0% used scores 25 and 100% or more scores 0.
- **Reward : risk (25):**
  - Risk = price − support. Support is the higher of today's low and the put wall below the price. Risk is at least 0.25 × ATR.
  - Target = today's low + 1 ATR, capped at the call wall when the wall is above the price and lower.
  - Score: 3:1 or better = 25, scaled linearly below that.
  - Show R:R, risk %, reward %, stop and target on the row.
- **Session phase (20):** points for the latest session's phase (section 9), times that phase's %: Bull Distribution 20, Bull Absorption 16, Bear Absorption 12, Bull Manipulation 10, Bull Accumulation 8.
- **Long score (30):** long score × 0.3.

### 7.3 Signals

Checked in this order:

1. **Sell now** (exit the long) if any of these:
   - an Early Bearish Reversal below the 1h 20 EMA, with a bias that is not Bullish;
   - the price at or above the call wall (target hit);
   - 100% or more of the ATR range used, into a Bear session phase of 60% or more.
2. **Ready to sell** if any of these:
   - an Early Bearish Reversal;
   - below the 1h 20 EMA with a Bearish bias;
   - 100% or more of the ATR range used;
   - earnings within 3 trading days;
   - a Bear Distribution or Bear Manipulation session of 70% or more.
3. **Buy now:** Strong Uptrend or Early Bullish Reversal, Zoya compliant, a bias that is not Bearish, through the breakout level, above the 1h 20 EMA, the latest session in a Bull phase, R:R ≥ 1.5, and under 80% of the range used.
4. **Ready to buy:** the same trend, compliance and bias, R:R ≥ 1, and either above the 1h 20 EMA or within 3% of the breakout. Show what a Buy now still needs (e.g. "breakout 1.2% away").
5. **Hold:** a Strong Uptrend or Early Bullish Reversal still intact (above the 1h 20 EMA or a bias that is not Bearish) but not a fresh entry.
6. **Wait:** anything else. A Wait shows an **Expected buy / stop / target**:
   - Buy = the breakout level (or the price if it is already through).
   - Stop = support (today's low or the put wall), at least 0.25 ATR under the buy.
   - Target = buy + 1 ATR, capped at the call wall above the buy.

### 7.4 Ordering, super stock and filters

- **Ordering:** by signal group in the order **Buy now, Ready to buy, Ready to sell, Sell now, Hold, Wait**; then setup score; then long score; then a wider SMA50/SMA200 gap.
- **Super stock:** the highest setup among Buy now names (or Ready to buy when there is no Buy now). It is **always pinned first** whatever the sort, with a gold outline and a "★ Super stock · lowest risk, highest reward" badge.
- **Toolbar:**
  - Ticker search.
  - Signal filter: buy and sell (default), buy only, sell only, Hold, Wait, all.
  - Today's state filter.
  - Sort: best setup (default), range meter (most room), reward to risk, session phase, long score, quality, closest to breakout, from yesterday, relative volume.
- **Each row shows:** exchange; ticker (a link) and name; Long/Flat tag; quality tag; trend badge; signal badge with its note; rank, setup and long score; R:R line; session phase line; the indicator readings; ATR%/float/spread/dollar volume; earnings; a Data toggle (relative volume, 1h trend, next earnings, to breakout, Quantum Shift reading, trend note); Zoya badge with last scanned time; price; range meter (bar, % used, left, RVol 10d/30d); current state; change from yesterday; call wall; King; put wall; expiry; bias with 15m/1h/4h arrows and the options book (total, calls, puts, call/put ratio); Remove (for added names) and Chart links.
- **Clicking a ticker** (or its session-phase line) opens the **Sessions tab on that stock and loads it immediately**.

## 8. Option chains and GEX levels

- **Source:** Cboe first (`SCREENER_OPTIONS_SOURCE=cboe`, the default), Yahoo v7 options as the fallback (`yahoo` = Yahoo only).
- **When chains are read:** once a day per name, at 8:00 AM ET on weekdays, and on the first screen a new name appears in.
  - Only an answer from the source counts as "tried today". A failed request is retried on the next screen.
  - A name with no listed options is tried once a day.
- **GEX levels:** use the expiries in the next **45 days** (at least the nearest, at most 8), and sum open interest **by strike across those expiries**.
  - **Call wall:** the strike with the most call OI.
  - **Put wall:** the strike with the most put OI.
  - Ties go to the strike nearer the spot.
  - **King:** the strike with the largest absolute net gamma exposure. Per contract, gamma × OI × 100 × S² × 1%, Black-Scholes gamma, IV defaulting to 50%, calls positive and puts negative.
  - Each level shows its OI and % of all OI in the window.
  - **Expiry:** the expiry that holds the most OI.
  - Show "as of" when the chain could not be refreshed today.
- **GEX levels table:** every Core name, with state and bias filters (default "Any").
- **Chain button on each Core row:** opens the stock's chain under its row.
  - Put OI bars to the left, call OI bars to the right, by strike, for the 30 strikes nearest the price.
  - The call wall, put wall and King are tagged, and a dashed line marks the price.
  - Per strike: IV for calls and puts, and net gamma.
  - Expiry chips: "All ≤45 days" or one expiry, each with its OI.
  - Fetched on demand and kept for 15 minutes.
  - Endpoint: `GET /api/chain/{ticker}?expiry=ALL`.
- **Diagnostics:**
  - When chains are missing, a note above the lists says why (e.g. "Cboe answered HTTP 403…", "Option chains are off: SCREENER_YAHOO_CRUMB is false").
  - `GET /api/chains/status` shows the source used, the Cboe and Yahoo errors and the last run's counts.
  - `POST /api/chains/refresh` reads all chains again.

## 9. Session phases indicator (Sessions tab)

- **Sessions in ET:** Asia 6:00 PM–2:00 AM (belongs to the next trade date), London 2:00–8:00 AM, New York 8:00 AM–5:00 PM. Stocks and ETFs have no Asia session.
- **Inputs:** always score from **5-minute candles with extended hours (1 month)**, so the boxes and percentages are identical on every chart timeframe (5m, 15m, 1h, 4h, 1D).
- **"Usual":** the average of the last 10 completed sessions of the same kind, for range, volume and path (the sum of the bars' high − low). Effort = volume ÷ usual volume, or path ÷ usual path when there is no volume (forex).
- **The four phases, each 0–100%:**
  - **Accumulation** = clamp((1.25 − range/usual) / 0.75) × (1 − 0.5 × body/range) × clamp((2.5 − effort) / 1.5). Bull if the close is in the upper half of the range.
  - **Manipulation:** the session sweeps the previous session's high (or low) and closes back inside. Score = clamp((high − close) / range / 0.6) for a high sweep (**Bear**), or clamp((close − low) / range / 0.6) for a low sweep (**Bull**).
  - **Absorption** = clamp(effort − 1) × clamp(1 − (body/usual range) / 0.5). Bull if the close is in the upper half of the range.
  - **Distribution** = clamp((body/range) / 0.7) × clamp(range/usual). Bull if the close is above the open.
- **Dominant phase:** the highest score; ties go manipulation > distribution > absorption > accumulation. Label it like "NY · 100% Bear Manipulation".
- **Chart:** candles on a candle-index x-axis, with a time→x mapping so the boxes align on any timeframe.
  - Each session is a box from its high to its low, filled with its phase colour (opacity scaled by %).
  - Green border for Bull, red for Bear, dashed while live.
  - Labels sized to the box width: "London 84% Bear Distribution", then "A x% · M x% · Ab x% · D x%".
  - A hover tooltip shows the phase bars, H/L, range vs usual and the sweep note.
  - A session table sits below the chart.
- **Controls:** symbol (default ES=F; works for stocks, futures, forex like EURUSD=X, and crypto), timeframe buttons, days 2/3/5/10.
- **Endpoint:** `GET /api/sessions/{symbol}?tf=&days=1..15`.
- **Per stock:** the latest session (a live one counts after 30 minutes) supplies the row's session phase line and the setup score's phase points.

## 10. PV Confirm Signals (nested under the uptrend check)

- **Basics:** daily bars, long only. **Uptrend = SMA50 > SMA200** (the same check, unchanged). Today's daily bar counts as complete only from **4:15 PM ET**.
- **Uptrend section, the four rules** (on a completed bar):
  1. close > SMA200;
  2. SPY close > SPY SMA200;
  3. RSI(2) < 10 (Wilder);
  4. volume < its 50-day average (the average includes that day).
- **BUY:** all four pass. Entry = the **next bar's open**. Stop = entry − **2.5 × ATR(14)** of the signal bar.
- **Exits,** checked on each completed bar from the entry bar on, in this order:
  - **STOP:** the low touches the stop. Filled at the stop, or at the open if the day gapped below it.
  - **TAKE PROFIT:** the first close above the entry.
  - **TIME EXIT:** at the close of the 10th bar held.
- **Statuses:**
  - **READY TO BUY:** the other three rules pass and RSI(2) is under 20 but not yet under 10.
  - **BUY:** shown with an estimated entry and stop until the next open.
  - **IN TRADE:** with the entry date and bar count. If the BUY was yesterday and today's live bar has opened, it is IN TRADE at today's open.
  - **SELL:** with the reason, on the exit bar. If a new BUY fires on the same bar, show SELL with a note that a new BUY also fired.
  - **WAIT:** anything else.
- **Near Setup:** 3 of 4 rules pass while flat.
- **Expected levels:** for WAIT and READY TO BUY, show:
  - **Expected buy:** the highest next close that would put RSI(2) under 10. Solve it from the Wilder averages: with average gain g, average loss l and length n, the needed drop d satisfies `(l(n−1)+d)/n > g(n−1)/n × (100−T)/T`. If RSI(2) is already under 10, use the last close.
  - **Expected stop:** that buy − 2.5 ATR.
  - **Expected target:** the 5-day average including that close (buy + 0.5 ATR if that is not above the buy).
  - Also list what else must hold: SPY back above its SMA200, the dip not breaking the stock's SMA200, and volume under its average.
- **Columns:** Stock, Price, PV Status, Entry, Stop, Target, RSI(2), Volume vs Avg %, Rules Passed (x/4 with ✓/✗ per rule). Filter chips (**All | Ready to Buy | Buy | In Trade | Sell | Near Setup**) appear **inside the Uptrend section only**.
- **Downtrend section** (SMA50 ≤ SMA200):
  - Do **not** run PV Confirm for new entries. Status is **"N/A – Downtrend"**.
  - **Exception:** a trade opened in the uptrend that later flips to downtrend is still tracked until its SELL (STOP / TAKE PROFIT / TIME EXIT).
- **Trade state:** rebuilt by replaying the last 40 daily bars, so it survives restarts. Open trades are also saved and kept on the list even if the stock leaves the screen ("Off screen").
- **Universe:** screened, Zoya-compliant names, plus open trades.
- **Schedule:** runs a few minutes after each screen, and at 4:15 PM ET, when every BUY, SELL and READY TO BUY is sent **once** as an alert.
- **Settings panel** (saved to a file, validated): RSI length, buy level, ready level, SMA length, volume average days, volume % limit, ATR length, stop multiple, time-exit bars, SPY rule on/off, alerts on/off; with Defaults and "Save and re-run".
- **Endpoints:** `GET /api/pv`, `POST /api/pv/refresh`, `GET|PUT /api/pv/settings`, `GET /api/pv/alerts`.

## 11. Market bias bot

### 11.1 The 9:30 AM call

- **When:** at **9:30 AM ET each trading day**, call the day **Bullish / Bearish / Neutral**. Use only data that finished by 9:30, even on a late start or a delayed job.
- **Scoring (max ±10):**

| Factor | Points |
|---|---|
| S&P futures (ES=F) overnight, from the last 5-minute bar before 4:00 PM ET on the previous trading day | ±2 beyond 0.3%, ±1 beyond 0.1% |
| Nasdaq futures (NQ=F) overnight | ±1 beyond 0.3% |
| SPY pre-market gap vs the prior close | ±1 beyond 0.25% |
| SPY last close vs its 20-day EMA | ±1 |
| SPY regime | +1 above SMA200 with SMA50 > SMA200; −1 below SMA200 |
| SPY 5-day change | ±1 beyond 1% |
| VIX | −1 if above 25 or more than 10% above its 10-day average; +1 if under 16 and below its 10-day average |
| ES London session phase (section 9) | ±1 for a Bull/Bear phase of 60% or more |
| ES Asia session phase | ±1 for a Bull/Bear phase of 60% or more |

- **Labels:** +2 or more is Bullish, −2 or less is Bearish, otherwise Neutral (with its lean). Strength: |score| 6+ strong, 4–5 moderate, else weak.
- **Before 9:30:** the card shows a preview, refreshed every 5 minutes.
- **After a late start:** make the call on startup, marked "late", with no phone alert.
- **Scoring the call:** after 4:20 PM ET, record SPY's open-to-close move and mark the call right or wrong. Neutral days are not scored. Show the hit rate.

### 11.2 Hourly intraday readings

- **When:** on the hour, 10:00 AM to 4:00 PM ET. Fill in missed hours after a late start.
- **Scoring (max ±10):**

| Factor | Points |
|---|---|
| SPY vs its opening range (9:30–10:00) | +2 above the high, −2 below the low |
| SPY vs VWAP | ±1 |
| QQQ vs VWAP | ±1 |
| IWM vs VWAP | ±1 |
| SPY change from the open | ±1 beyond 0.3% |
| SPY 15-minute trend: close vs a rising/falling 20 EMA (vs 3 bars ago) | ±1 |
| Breadth, Nasdaq + NYSE advancers/decliners (live only) | +1 at 1.5 or more, −1 at 0.67 or less |
| VIX vs its own open | −1 up 5% or more, +1 down 5% or more |
| ES New York session phase | ±1 for 60% or more |

- **Display:** a timeline on the card (9:30 call, then each hour, then "now" on refresh), with flips highlighted. The phone alert goes out **only when the bias flips**.

### 11.3 Card, command line and endpoints

- **Card:** "Market bias today" at the top of the Screen tab. It shows the big label and score, the factor table with points, the hourly timeline, the last 10 calls with SPY's result and right/wrong, the hit rate, the phone-channel status, a "Check now" button and a "Send test alert" button.
- **Command line / scheduled job:** a standalone script that prints the 9:30 call without the web server. `--intraday` prints the last whole hour's reading and whether it flipped from the hour before (from the 9:30 call at 10:00). `--json` prints JSON. On a non-trading day it prints "MARKET CLOSED".
- **Endpoints:** `GET /api/bias` (`?refresh=true` also includes a live intraday reading), `POST /api/bias/test`.

## 12. Trading plan bot (top-down, FVG / order blocks / Fibonacci)

A long-only plan read top-down, scanned every **15 minutes from 9:45 AM to 3:45 PM ET** on trading days over every compliant screened stock. Every rule uses only candles that had finished by the scan time.

**Zones** (on 15-minute and 1-hour candles, formed in the last 60 candles):
- **Bullish fair value gap (FVG):** three candles where the first candle's high is below the third candle's low; the zone is that gap. Bearish FVG: the first candle's low is above the third's high.
- **Bullish order block (OB):** the last down candle (close below open) before a displacement up, meaning a close within the next 3 candles at least 1 ATR above that candle's high. The zone is the candle's low to high. Bearish OB is the mirror.
- Ignore gaps under 0.1 ATR. A zone is gone (mitigated) once a later candle closes through it: below a bullish zone's low, or above a bearish zone's high. The zone forms on the candle that completes it, and only closes after that count.

**The plan:**
1. **Daily direction:** Strong Uptrend or Early Bullish Reversal only (section 6, as of the prior close).
2. **Liquidity above = the target:** the prior day's high, the highest high of the last 20 hourly candles, the lower edge of the nearest bearish FVG or OB above (1h or 15m), and the call wall when known. Take the nearest one above the entry. If it is closer than the minimum R:R (2R), **skip**: supply is in the way. With nothing above, the target is entry + 2R.
3. **Setups, checked in this order:**
   - **Retest:** in the last three 15m candles price dipped into an unmitigated bullish FVG or OB (15m or 1h, formed before those candles) and the last 15m close is still above the zone's low.
   - **Fibonacci golden zone:** on the 1h chart, take the highest high of the last 40 hours (at least 2 hours old) and the lowest low in the 40 hours before it. The leg must be at least 2 × ATR(1h) (a trend, not a range). Price dipped into the 0.5–0.618 retracement in the last three 15m candles and no 15m candle since the high closed below 0.786 (that kills the setup). The stop goes just under 0.786.
   - **Reversal:** in the last six 15m candles, one went below the lowest low of the 20 before it (or below the prior day's low) and closed back above it, and the last 15m close is above that candle's high. The stop goes under that candle's low.
   - **Breakout:** the last 15m candle closed above the prior day's high for the first time, on at least 1.5× its 20-candle average volume.
4. **5-minute confirmation:** one of the last three 5m candles is bullish and closes above its 20 EMA and above the previous candle's high.
5. **Entry, stop:** entry at the next 5m open. Stop = the lower of the setup's level (zone low, 0.786, sweep low or breakout candle low) and the last six 5m lows, minus 0.1 × ATR(15m). For the Fibonacci setup the stop is just under 0.786. The stop may be at most 3% away.
6. **Grade A/B/C** by confluence, one point each:
   - 1h close above a rising 20 EMA;
   - a zone from the 1h chart;
   - an FVG and an OB overlapping;
   - an entry inside the Fibonacci golden zone;
   - SPY's 15m close above its 20 EMA;
   - confirmation-candle volume at 1.2× the 5m average or more;
   - R:R of 3 or better.

   4+ points is A, 2–3 is B, otherwise C.

**Management and exits:**
- **Hold:** a swing trade, held up to `holdDays` trading days (default 5; 1 makes it a day trade).
- **Breakeven:** once the trade has been up 1R, the stop moves to the entry, from the next candle on.
- **Exits:** the stop (an overnight gap through it fills at that day's open), the target, or the 3:40 PM candle on the last day of the hold. When one candle reaches both the stop and the target, assume the stop.
- **One trade at a time per stock**, and at most one entry per stock per day.

**Backtest and "high probability only":**
- Replay the plan every 15 minutes over the last 60 days of 5-minute data for every compliant screened stock, entering at the next 5m open, with 5 bps cost per side.
- Report results by setup and grade (e.g. RETEST-A) and overall: trades, win rate, average R, average trade, profit factor.
- Run it after startup and at 6:30 PM ET on trading days.
- **Send a BUY NOW only for a setup-grade that has won at least 60% of at least 15 backtested trades** (both settings). If none qualifies, send nothing and say so on the card.

**Alerts:**
- **BUY NOW:** symbol, price, entry, stop, target, R:R, grade, and that grade's backtested win rate.
- **SELL NOW:** stop hit ("breakeven stop" after +1R), target hit, or the time exit on the last hold day. Include the result %.
- Alerts appear in the page and go to Telegram / ntfy when set up.

**Card on the Screen tab** ("Trading plan bot"):
- The plan in plain words.
- The gate status ("Alerts on for …" or why there are none).
- Open plan trades: entry, stop (with "breakeven"), target, now, backtest win rate.
- Today's signals.
- "Setting up": zone retests waiting for a 5m confirmation, or setups blocked by liquidity or a wide stop, with the reason.
- The backtest table by setup and grade.
- Settings: minimum win rate, minimum trades, minimum R:R, largest stop %, cost, hold days.
- "Scan now" and "Re-run backtest" buttons.

**Endpoints:** `GET /api/plan`, `POST /api/plan/scan`, `POST /api/plan/backtest`, `PUT /api/plan/settings`, `GET /api/plan/alerts`.

## 13. Playbook (per stock) and the trader profile

**Trader profile** (saved; editable on the Playbook panel and at `GET|PUT /api/profile`): account size **$9,000**, risk **2%** per trade (= **$180**), minimum reward-to-risk **1:2**.

**Playbook** (a "Playbook" button on each Core row, and `GET /api/playbook/{ticker}`): every strategy backtested on **that stock's own history**, what each says now, and a plan.

| Strategy | Backtest |
|---|---|
| **Breakout** (the Core Buy now idea) | 2 years of daily bars. In a Strong Uptrend or Early Bullish Reversal (as of the prior close), buy when price trades above the prior day's high (a buy stop; the open if it gaps above). Stop = the higher of the prior day's low and entry − 1 ATR(14). Target = entry + the profile's minimum R:R (at least 1.5R). Hold up to 5 days, breakeven after +1R; a day that reaches both counts as the stop. One trade at a time |
| **PV Confirm** | 2 years of daily bars, the full-history replay of section 10 |
| **Trading plan bot** | As a swing trade (its hold days) and as a day trade, 60 days of 5-minute data (section 12), with its minimum R:R at least the profile's |
| **Quantum Shift**, long only | 60 days of hourly bars (section 15) |

- **Verdict for each strategy on that stock:**
  - **edge:** at least 8 trades, a profit factor of 1.3+ and a positive average trade;
  - **weak:** a profit factor of 1–1.3;
  - otherwise **no edge**, or **too few trades**.
- **Shown per strategy:** trades, win rate, profit factor, average and total return, and what it says now (the Core signal, the PV status, the plan bot's setup or watch, the Quantum Shift regime).
- **Active now:**
  - Breakout: the Core signal is Buy now or Ready to buy.
  - PV Confirm: BUY or READY TO BUY.
  - Plan bot: a setup now.
  - Quantum Shift: regime up with a shift up in the last 6 hours.
- **The plan:** the active strategy with an **edge** on this stock whose R:R meets the profile minimum, picking the highest profit factor. It gives:
  - entry, stop, target, R:R and exit rules;
  - **shares = risk dollars ÷ (entry − stop), capped so the position never costs more than the account** (no margin), with a note when capped;
  - dollars at risk and dollars to target.
- **Otherwise "NO TRADE"** with the reason for each active strategy ("no edge on this stock", "only weak", "R:R under your 1:2 minimum").
- **Context line:** Zoya, trend class, bias, session phase, range used, earnings warning, options walls.

## 14. Alerts and notifications

- **Price alerts:** at or above, at or below, up % from close, down % from close. One-shot; checked on every 10-second tick; kept in a log.
- **Buy now / Sell now alerts:** during market hours (9:30 AM–4:00 PM ET on trading days), when a Core stock's signal is Buy now or Sell now, alert **once per stock, per signal, per day**. A Buy now alert carries the stop, target, R:R and ★ for the super stock; a Sell now alert says why. `GET /api/signal-alerts`.
- **PV Confirm alerts:** at 4:15 PM ET (section 10).
- **Market bias:** at 9:30, and hourly on flips (section 11).
- **Trading plan bot:** BUY NOW and SELL NOW every 15 minutes in market hours (section 12).
- **Delivery:**
  - In the page: a toast and a browser Notification, deduped by id in localStorage, with an Alerts tab badge.
  - Phone, when configured: **Telegram** (`SCREENER_TELEGRAM_TOKEN`, `SCREENER_TELEGRAM_CHAT_ID`, via `sendMessage`) and/or **ntfy** (`SCREENER_NTFY_TOPIC`, `SCREENER_NTFY_SERVER` default `https://ntfy.sh`; POST the body with a `Title` header).
  - Never put tokens in code; read them from environment variables.
- **Alerts tab:** price alert form and log, a "Turn on notifications" button, the trading plan bot alert table, the Buy now / Sell now alert table, and the PV Confirm alert table.

## 15. Other tabs

- **Analyze:** one ticker's full readout, including SEC EDGAR quarterly results (revenue, margins, EPS vs a year earlier).
- **Technicals:** a candle chart (daily over 2 years so the 200-day line spans the chart), with a table per timeframe: close, 20 EMA, trend, RSI, MACD, ADX, ±DI, Stoch, Band, ATR, candle count.
- **Backtest: Quantum Shift indicator.**
  - Scoring: a 0–100 energy score.
    - Coil (30): Bollinger bandwidth percentile over 100 bars, at its lowest in the last 5 bars.
    - Break (30): |close − EMA20| in ATRs, 2 ATR = full.
    - Momentum (25): z-score of the 10-bar rate of change over 50 bars, |z| of 2 = full.
    - Volume (15): bar volume vs its 20-bar average, 2× = full.
  - **Shift up:** energy ≥ threshold and the close is above EMA20 + 1 ATR with positive momentum. The regime resets when the close crosses back through EMA20.
  - **Backtest rules, long only:**
    - Entry at the next open; initial stop 2 ATR.
    - Chandelier trail at 2.5 ATR off the best close; it never loosens.
    - Exits on a close through the stop, an opposite shift, or 35 bars.
    - 5 bps cost per side.
    - Defaults: 60 days of 1h bars, energy 60.
  - **Report:** totals (trades, win rate, average trade, profit factor, equal-weight return vs buy-and-hold, average hold), a per-stock table and a trade list.
  - Endpoints: `GET /api/backtest`, `GET /api/backtest/last`.
- **APP Details:** a plain-language explanation of every rule above, and an API guide.

## 16. Header and market line

- **Status line:** "Nasdaq and NYSE · N compliant of M · time · refreshes every 15 min, 7:00 AM–4:00 PM ET trading days".
- **Filter chips** for the screen filters.
- **Market line:** SPY, QQQ and IWM against today's VWAP (up/down), and Nasdaq/NYSE advancers vs decliners. Regime = Bearish if more than half of the ETFs are below VWAP, otherwise Bullish.
- **Notes line:** says when TradingView is unreachable, or why option chains are missing.

## 17. Settings (environment variables)

| Variable | Meaning |
|---|---|
| `PORT` | HTTP port, if the platform needs one |
| `ZOYA_SOURCE`, `ZOYA_API_KEY`, `ZOYA_API_URL` | Zoya page (default) or API |
| `SCREENER_YAHOO_CRUMB` | Yahoo cookie session on/off (default on) |
| `SCREENER_OPTIONS_SOURCE` | `cboe` (default) or `yahoo` |
| `SCREENER_SEC_USER_AGENT` | name and contact email for SEC EDGAR |
| `SCREENER_TELEGRAM_TOKEN`, `SCREENER_TELEGRAM_CHAT_ID` | Telegram alerts |
| `SCREENER_NTFY_TOPIC`, `SCREENER_NTFY_SERVER` | ntfy alerts |

## 18. API summary

`GET /api/screen` · `GET /api/prices` (polled every 10 s; includes the latest price, the alerts that rang, PV alerts, Buy/Sell alerts and the latest bias reading) · `POST /api/refresh` · `GET|POST /api/watchlist` · `DELETE /api/watchlist/{t}` · `GET /api/watchlist/export` · `GET|POST /api/alerts` · `DELETE /api/alerts/{id}` · `GET /api/analyze/{t}` · `GET /api/technicals/{t}` · `GET /api/backtest` · `GET /api/sessions/{s}?tf=&days=` · `GET /api/zoya/{t}` · `GET /api/chain/{t}?expiry=` · `GET /api/chains/status` · `POST /api/chains/refresh` · `GET /api/pv` · `POST /api/pv/refresh` · `GET|PUT /api/pv/settings` · `GET /api/pv/alerts` · `GET /api/signal-alerts` · `GET /api/bias` · `POST /api/bias/test` · `GET /api/plan` · `POST /api/plan/scan` · `POST /api/plan/backtest` · `PUT /api/plan/settings` · `GET /api/plan/alerts` · `GET /api/playbook/{t}` · `GET|PUT /api/profile` · `GET /api/config` · a health check.

## 19. What to deliver

1. The working app: the backend routes, the scheduled jobs (in New York time, skipping weekends and the holidays), the persistent storage and the page.
2. Unit tests for every calculation and rule, all passing.
3. A short README: how to run it, every setting, how to set up ntfy or Telegram, and how the scheduled jobs are configured.
4. Nothing here is financial advice and nothing places orders; say so on the APP Details tab.

**Check before finishing:**
- Every tab loads with no console errors.
- The Core list shows only compliant Strong Uptrend / Early Bullish Reversal names, with signals in the stated order.
- The market-bias card shows a preview before 9:30.
- The plan-bot card shows its backtest table and gate.
- A Playbook panel sizes a trade from the profile ($180 risk, capped at $9,000) and rejects anything under 1:2.
