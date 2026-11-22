# Personal Screener 2026

A Spring Boot app that screens Nasdaq and NYSE stocks with TradingView, checks each name on Zoya, and ranks the uptrends. Each row shows quality, technicals, intraday trend and option GEX levels. Prices refresh every 10 seconds and option chains load at 8:00 AM ET.

Tabs: **Screen**, **Alerts**, **Analyze** (SEC EDGAR results), **Technicals**, **APP Details** (every rule, plus the API guide).

## Run it

```bash
mvn spring-boot:run
```

Open http://localhost:8080. The first screen runs at startup and then every 30 minutes, Monday 7:00 AM to Friday 8:00 PM ET.

## Settings

| Variable | What it does |
| --- | --- |
| `ZOYA_API_KEY` | Zoya API key. Without it every name reads "Not checked" and no name can be the super core. |
| `ZOYA_API_URL` | Defaults to `https://api.zoya.finance/graphql` (use the sandbox URL while testing). |
| `SCREENER_DATA_DIR` | Where the watchlist, alerts and caches live. Default `./data`. On Azure use `/home/data`. |
| `SCREENER_SEC_USER_AGENT` | SEC asks for a name and contact email, e.g. `MyScreener you@example.com`. |
| `SCREENER_YAHOO_CRUMB` | `true` (default) uses Yahoo's session for bid/ask, option chains and earnings dates. |
| `PORT` | HTTP port, default 8080. |

The filters, quality clips and fallback ticker list are in `src/main/resources/application.yml` under `screener:`.

## What it does

1. **Screen.** TradingView filters on price ($1.50–$20), market cap (over $300M), debt/equity (under 0.5) and revenue growth (over 5%). The app then checks target upside (over 25%), debt/assets (under 0.3), debt/capital (under 0.3) and cash/debt (over 1) from the same data. If TradingView can't be reached, it screens `screener.fallback-universe` instead and labels those names as not verified.
2. **Zoya.** Compliance for every name, cached for 24 hours.
3. **Analysis.** Yahoo candles (completed only): 50/200-day averages, RSI, MACD, ADX with ±DI, stochastic, Bollinger position, ATR, the 1-hour 20 EMA, and trend on 15m, 1h and 4h candles.
4. **Lists.**
   - **Top 10:** the largest 50-day-over-200-day gaps.
   - **Core:** every other uptrend.
   - **Super core:** compliant, above the 1h 20 EMA, at least 1× relative volume, more than 3 trading days from earnings, and a quality pass. The name closest to its breakout wins.
5. **GEX.** The next listed expiry gives the call wall, put wall and King (the strike with the largest net gamma), each as a share of open interest.

## API

`GET /api/screen` · `GET /api/prices` · `POST /api/refresh` · `GET|POST /api/watchlist` · `DELETE /api/watchlist/{t}` · `GET /api/watchlist/export` · `GET|POST /api/alerts` · `DELETE /api/alerts/{id}` · `GET /api/analyze/{t}` · `GET /api/technicals/{t}` · `GET /actuator/health`

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
