# Personal Screener 2026

A Spring Boot app that screens Nasdaq and NYSE stocks with TradingView, checks each name on Zoya, and ranks the uptrends. Each row shows quality, technicals, intraday trend and option GEX levels. Prices refresh every 10 seconds and option chains load at 8:00 AM ET.

Tabs: **Screen**, **Alerts**, **Analyze** (SEC EDGAR results), **Technicals**, **APP Details** (every rule, plus the API guide).

## Run it

```bash
mvn spring-boot:run
```

Open http://localhost:8080. The first screen runs at startup and then every 15 minutes on trading days from 7:00 AM to 4:00 PM ET (7:00, 7:15, … 4:00).

## Settings

| Variable | What it does |
| --- | --- |
| `ZOYA_SOURCE` | `page` (default) reads `https://zoya.finance/stocks/<ticker>`, no key needed. `api` uses Zoya's GraphQL API instead. |
| `ZOYA_API_KEY` | Zoya API key, used only with `ZOYA_SOURCE=api`. |
| `ZOYA_API_URL` | Defaults to `https://api.zoya.finance/graphql` (use the sandbox URL while testing). |
| `SCREENER_DATA_DIR` | Where the watchlist, alerts and caches live. Default `./data`. On Azure use `/home/data`. |
| `SCREENER_SEC_USER_AGENT` | SEC asks for a name and contact email, e.g. `MyScreener you@example.com`. |
| `SCREENER_YAHOO_CRUMB` | `true` (default) uses Yahoo's session for bid/ask, option chains and earnings dates. |
| `SCREENER_OPTIONS_SOURCE` | `cboe` (default): option chains from Cboe delayed quotes, Yahoo as the fallback. `yahoo`: Yahoo only. |
| `SCREENER_NTFY_TOPIC` | ntfy topic for phone alerts (market bias at 9:30 AM ET). |
| `SCREENER_TELEGRAM_TOKEN`, `SCREENER_TELEGRAM_CHAT_ID` | Telegram bot token and chat id for phone alerts. |
| `PORT` | HTTP port, default 8080. |

The filters, quality clips and fallback ticker list are in `src/main/resources/application.yml` under `screener:`.

## What it does

1. **Screen.** TradingView filters on price ($1.50–$20), market cap (over $300M), debt/equity (under 0.5) and revenue growth (over 5%). The app then checks target upside (over 25%), debt/assets (under 0.3), debt/capital (under 0.3) and cash/debt (over 1) from the same data. If TradingView can't be reached, it screens `screener.fallback-universe` instead and labels those names as not verified.
2. **Zoya.** For every name the app reads its Zoya page, e.g. `https://zoya.finance/stocks/mitk` ("MITK stock is Shariah-compliant"), and caches the status for 24 hours in `zoya-cache.json`, so each ticker is looked up at most once a day, even across restarts. `GET /api/zoya/MITK` shows one ticker (`?refresh=true` looks it up again now); `GET /api/zoya` lists the cache.
3. **Analysis.** Yahoo candles (completed only): 50/200-day averages, RSI, MACD, ADX with ±DI, stochastic, Bollinger position, ATR, the 1-hour 20 EMA, and trend on 15m, 1h and 4h candles.
4. **Lists.**
   - **Core:** long only. Every name that passes the screen, is Zoya compliant and is in a Strong Uptrend or Early Bullish Reversal, with no cap; names that are not compliant are never displayed. Each gets a signal, listed in this order: **Buy now**, **Ready to buy**, **Ready to sell**, **Sell now**, **Hold**, **Wait** (a Wait shows the expected buy, stop and target) (rules in the app's APP Details tab), and the list shows the buy and sell signals by default. Within a signal, names sort by a 0–100 setup score (low risk, high reward): room left on the range meter (25), reward to risk from support (today's low or the put wall, at least ¼ ATR) to target (low + 1 ATR, or the call wall) (25), the latest session phase (20) and the long score (30). The **super stock**, the best Buy now (or Ready to buy) name, always sits on top.
   - **Trend class** (each row's colour), from completed daily candles: Strong Uptrend, Early Bullish Reversal, Early Bearish Reversal or No Uptrend. Fewer than 200 daily candles stays No Uptrend.
   - RSI, MACD, ADX, stochastic and Bollinger position show on every row. They are readings, not filters.
5. **GEX.** Each name's option chain is read once a day (8:00 AM ET on weekdays, or on the first screen a new name appears in). Open interest is summed by strike over the expiries in the next 45 days: the call wall is the strike with the most call open interest, the put wall the strike with the most put open interest, and King the strike with the largest net gamma. Expiry is the date holding the most open interest. Chains come from Cboe's free delayed quotes (`cdn.cboe.com`, no login), with Yahoo as the fallback (needs its cookie session, `SCREENER_YAHOO_CRUMB=true`, the default). `SCREENER_OPTIONS_SOURCE=yahoo` uses Yahoo only. The **Chain** button on each Core row opens the stock's chain under it (open interest by strike, puts left and calls right, with the walls, King and price marked; all expiries or one). If chains are missing, the note above the lists says why; `GET /api/chains/status` has the details and `POST /api/chains/refresh` reads them again.

## Magnet zones (multi-timeframe volume profile)

Purple bands on the Sessions chart. Five volume profiles (yesterday, 5 days and 20 days of 5-minute candles, 3 months and 1 year of daily candles) each give a magnet zone: the band around the point of control holding 20% of the volume. Where two or more timeframes overlap is drawn, darker the more agree. `GET /api/magnet/{symbol}?days=20` returns the profiles, the zones and a walk-forward test: how often price touched the strongest zone, and on days opening more than 1 ATR away, how often it reached it against a mirror band. In testing (Sep–Nov) the combined zones were touched more often than single ones but did not pull price from far away, so they work as levels, not targets.

## Playbook (per stock)

The **Playbook** button on each Core row backtests every strategy on that stock's own history: the daily Breakout (the Core Buy now idea; 2 years), PV Confirm (2 years), the trading plan bot as a swing and a day trade (60 days of 5-minute data) and Quantum Shift long only (60 days of hourly bars). Each is labelled **edge** (8+ trades, profit factor 1.3+, positive average trade), weak, no edge or too few trades, with what it says now. The plan comes from the best strategy that has an edge on the stock and is active now: entry, stop, target, R:R and the share count from your **profile**: account size, risk % per trade and minimum R:R (default $9,000, 2% = $180, 1:2), edited on the panel or `PUT /api/profile`. Shares = risk dollars ÷ (entry − stop), capped so the position never costs more than the account. Plans under the minimum R:R are rejected, and the Breakout is backtested with its target at that minimum. Otherwise it says no trade. `GET /api/playbook/{ticker}`.

## Trading plan bot (FVG / order blocks, top-down)

A long-only day-trade plan read top-down: **daily** direction (Strong Uptrend or Early Bullish Reversal), **daily/1h** liquidity above as the target (prior day high, 20-hour high, nearest bearish FVG or order block; skip when it is closer than 2R), **1h** trend, a **15m/1h** setup: a retest of an unfilled bullish FVG or order block, a pullback into the **Fibonacci golden zone** (0.5–0.618 of a 1h swing low to swing high of at least 2 ATR; a close below 0.786 kills it and the stop goes under 0.786), a **reversal** (a 15m sweep of the recent or prior-day low that closes back above, then a close above that candle's high), or a 15m breakout over the prior day high on 1.5× volume, and a **5m** confirmation (a bullish close above the 5m 20 EMA and the previous high). Entry at the next 5m open, stop under the zone or the 5m swing low (3% at most). Held as a **swing trade** for up to 5 trading days (the card's "hold days" setting; 1 makes it a day trade). **Management**: once up 1R the stop moves to breakeven. Exits at the stop (an overnight gap through it fills at the open), the target, or 3:45 PM on the last day of the hold.

Setups are graded A/B/C by confluence and backtested on every screened, compliant stock over the last 60 days of 5-minute data. Every 15 minutes from 9:45 AM to 3:45 PM ET the bot scans the screener and sends **BUY NOW** (entry, stop, target, grade, backtested win rate) only for grades that have won at least 60% over at least 15 trades, and **SELL NOW** when an open plan trade must close. Both thresholds are on the card's settings. `GET /api/plan`, `POST /api/plan/scan`, `POST /api/plan/backtest`, `PUT /api/plan/settings`, `GET /api/plan/alerts`.

## Buy now / Sell now alerts

During market hours (9:30 AM to 4:00 PM ET on trading days), when a Core stock's signal turns **Buy now** or **Sell now**, the app alerts once per stock, per signal, per day: a pop-up and browser notification in the page, and Telegram or ntfy on your phone when set up (see below). A Buy now alert carries the setup's stop and target. The Alerts tab lists them; `GET /api/signal-alerts` returns them.

## Market bias bot

At 9:30 AM ET each trading day the app calls the day **Bullish**, **Bearish** or **Neutral** from S&P and Nasdaq futures overnight, SPY's premarket gap, SPY against its 20-day EMA and 200-day average, SPY's 5-day change, VIX, and the ES London and Asia session phases. It alerts in the page (card at the top of the Screen tab, and a browser notification) and, when set up, on your phone:

- **ntfy** (simplest): install the ntfy app, subscribe to a topic name only you know, and start the app with `SCREENER_NTFY_TOPIC=<that topic>`.
- **Telegram**: create a bot with @BotFather, send it a message, and start the app with `SCREENER_TELEGRAM_TOKEN=<bot token>` and `SCREENER_TELEGRAM_CHAT_ID=<your chat id>`.

**During the session**, on the hour from 10:00 AM to 4:00 PM ET, it reads the tape again (SPY against its opening range and VWAP, QQQ and IWM against VWAP, SPY from the open, SPY's 15-minute trend, breadth, VIX from its open, and the ES New York session) and shows the readings as a timeline on the card. The phone alert goes out only when the bias flips; `scripts/market-bias.sh --intraday` prints the latest hourly reading.

"Send test alert" on the card checks the phone channels. `scripts/market-bias.sh` prints the call from the command line without starting the app (a scheduled Claude task uses it to send the call to the Claude app at 9:30 AM ET). After the close the app records SPY's open-to-close move and whether the call was right, and the card shows the hit rate.

## PV Confirm Signals

Nested under the trend check (uptrend = SMA50 above SMA200). Daily bars, long only.

- **Uptrend:** close above SMA200, SPY above its SMA200, RSI(2) under 10 and volume under its 50-day average. All four on a completed bar is a **BUY** at the next open; stop = entry − 2.5 × ATR(14). Exits: **STOP** (low touches the stop), **TAKE PROFIT** (first close above the entry) or **TIME EXIT** (10 bars). Statuses: READY TO BUY (three rules pass and RSI(2) under 20), BUY, IN TRADE, SELL, WAIT; chips filter All, Ready to Buy, Buy, In Trade, Sell and Near Setup (3 of 4 rules).
- **Downtrend:** no new entries (N/A – Downtrend). A trade opened in the uptrend is still tracked to its SELL after the trend flips, even if the name leaves the screen.
- Today's bar counts from 4:15 PM ET, when each BUY, SELL and READY TO BUY is sent once as an alert. Thresholds live on the settings panel and in `pv-settings.json`.

## API

`GET /api/screen` · `GET /api/chain/{t}?expiry=ALL` · `GET /api/chains/status` · `GET /api/pv` · `POST /api/pv/refresh` · `GET|PUT /api/pv/settings` · `GET /api/pv/alerts` · `GET /api/prices` · `POST /api/refresh` · `GET|POST /api/watchlist` · `DELETE /api/watchlist/{t}` · `GET /api/watchlist/export` · `GET|POST /api/alerts` · `DELETE /api/alerts/{id}` · `GET /api/analyze/{t}` · `GET /api/technicals/{t}` · `GET /actuator/health`

## Deploy to Azure App Service

```bash
mvn -B package
az webapp up --runtime "JAVA:21-java21" --sku B1 --name <your-app-name>
az webapp config appsettings set --name <your-app-name> --settings SCREENER_DATA_DIR=/home/data ZOYA_API_KEY=<key> SCREENER_SEC_USER_AGENT="<name> <email>"
```

Use a B1 plan or higher and turn on **Always On**, so the scheduled screens and the 8:00 AM chain run keep firing.

For GitHub Actions, `.github/workflows/azure-webapp.yml` builds and tests every push. To deploy `main` as well, add a repository variable `AZURE_WEBAPP_NAME` and a secret `AZURE_WEBAPP_PUBLISH_PROFILE`.

A `Dockerfile` is included for Azure Container Apps or any container host.

Market data is delayed and free. Nothing here is advice, and nothing places an order.
