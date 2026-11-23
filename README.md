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
| `PORT` | HTTP port, default 8080. |

The filters, quality clips and fallback ticker list are in `src/main/resources/application.yml` under `screener:`.

## What it does

1. **Screen.** TradingView filters on price ($1.50–$20), market cap (over $300M), debt/equity (under 0.5) and revenue growth (over 5%). The app then checks target upside (over 25%), debt/assets (under 0.3), debt/capital (under 0.3) and cash/debt (over 1) from the same data. If TradingView can't be reached, it screens `screener.fallback-universe` instead and labels those names as not verified.
2. **Zoya.** For every name the app reads its Zoya page, e.g. `https://zoya.finance/stocks/mitk` ("MITK stock is Shariah-compliant"), and caches the status for 24 hours in `zoya-cache.json`, so each ticker is looked up at most once a day, even across restarts. `GET /api/zoya/MITK` shows one ticker (`?refresh=true` looks it up again now); `GET /api/zoya` lists the cache.
3. **Analysis.** Yahoo candles (completed only): 50/200-day averages, RSI, MACD, ADX with ±DI, stochastic, Bollinger position, ATR, the 1-hour 20 EMA, and trend on 15m, 1h and 4h candles.
4. **Lists.**
   - **Core:** every name that passes the screen and is in an uptrend or a trend reversal, with no cap. Each gets a signal, **Buy now**, **Ready to buy**, **Sell now**, **Ready to sell**, **Hold** or **Wait** (rules in the app's APP Details tab), and the list shows the buy and sell signals by default. Within a signal, names sort by a 0–100 setup score (low risk, high reward): room left on the range meter (25), reward to risk from support (today's low or the put wall, at least ¼ ATR) to target (low + 1 ATR, or the call wall) (25), the latest session phase (20) and the long score (30). The **super stock**, the best Buy now (or Ready to buy) name, always sits on top.
   - **Trend class** (each row's colour), from completed daily candles: Strong Uptrend, Early Bullish Reversal, Early Bearish Reversal or No Uptrend. Fewer than 200 daily candles stays No Uptrend.
   - RSI, MACD, ADX, stochastic and Bollinger position show on every row. They are readings, not filters.
5. **GEX.** Each name's option chain is read once a day (8:00 AM ET on weekdays, or on the first screen a new name appears in). Open interest is summed by strike over the expiries in the next 45 days: the call wall is the strike with the most call open interest, the put wall the strike with the most put open interest, and King the strike with the largest net gamma. Expiry is the date holding the most open interest. Chains come from Yahoo and need its cookie session (`SCREENER_YAHOO_CRUMB=true`, the default).

## PV Confirm Signals

Nested under the trend check (uptrend = SMA50 above SMA200). Daily bars, long only.

- **Uptrend:** close above SMA200, SPY above its SMA200, RSI(2) under 10 and volume under its 50-day average. All four on a completed bar is a **BUY** at the next open; stop = entry − 2.5 × ATR(14). Exits: **STOP** (low touches the stop), **TAKE PROFIT** (first close above the entry) or **TIME EXIT** (10 bars). Statuses: READY TO BUY (three rules pass and RSI(2) under 20), BUY, IN TRADE, SELL, WAIT; chips filter All, Ready to Buy, Buy, In Trade, Sell and Near Setup (3 of 4 rules).
- **Downtrend:** no new entries (N/A – Downtrend). A trade opened in the uptrend is still tracked to its SELL after the trend flips, even if the name leaves the screen.
- Today's bar counts from 4:15 PM ET, when each BUY, SELL and READY TO BUY is sent once as an alert. Thresholds live on the settings panel and in `pv-settings.json`.

## API

`GET /api/screen` · `GET /api/pv` · `POST /api/pv/refresh` · `GET|PUT /api/pv/settings` · `GET /api/pv/alerts` · `GET /api/prices` · `POST /api/refresh` · `GET|POST /api/watchlist` · `DELETE /api/watchlist/{t}` · `GET /api/watchlist/export` · `GET|POST /api/alerts` · `DELETE /api/alerts/{id}` · `GET /api/analyze/{t}` · `GET /api/technicals/{t}` · `GET /actuator/health`

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
