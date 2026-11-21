# Halal Ratio Screener

A Spring Boot app that screens Nasdaq and NYSE stocks against a set of ratio rules, checks each match live with Zoya, and builds long or short trade plans from the daily levels.

## Rules

| Gate | Rule |
|---|---|
| Price | $1.50–$20 (inclusive) |
| Market cap | over $300M |
| Target upside | over 25% (analyst mean target ÷ price − 1) |
| Debt/equity | under 0.5 |
| Debt/assets | under 0.3 |
| Debt/capital | under 0.3 (debt ÷ (debt + equity)) |
| Cash/debt | over 1 (zero debt passes) |
| Revenue growth | over 5% |
| Zoya | `COMPLIANT` with a fresh read (under 24 hours) to make the screen list |
| Quality | 0–100 score and A–F grade. It is shown on every row and never removes a name |

A missing number fails its gate. Every threshold can be changed under `screener.rules` in `application.yml`.

## Features

- **Screen**: Nasdaq Trader's Nasdaq and NYSE lists → batch quote (price band + market cap) → the full ratio gates for each name → a live Zoya check on each name that passes the ratios → GEX levels on each listed name.
- **Zoya cache**: a good read is kept for 24 hours. A failed read never replaces a good one. A read older than 24 hours is shown as `STALE` and doesn't count as compliant.
- **Analyze**: gives a long or short plan from the prior day's high, low, close, floor pivot, R1/S1, ATR(14) and 20-day average. Shares are sized from the account and the stop: `floor(account × risk% ÷ |entry − stop|)`, capped by the account size.
- **GEX**: call wall, king and put wall, worked out from the nearest 4 option expiries (Black–Scholes gamma × open interest).
- **Core stocks** (same full screener row), two kinds:
  - *Screen core*: names that make the list with a fresh compliant Zoya read are kept for the New York day. The list resets when the New York date changes.
  - *My core*: names you add yourself (`POST /api/core/{sym}`) and remove (`DELETE /api/core/{sym}`). No gate or Zoya rule applies, so they can be non-compliant. They stay across days until you remove them, and each screen refreshes their rows. They appear in the snapshot with `mine=true`.
- **Daily snapshot**: side, bias, state, Zoya and quality for each screen-list and core name, saved as one file per New York day for a bot.
- **Public exports** (JSON, or CSV with `format=csv`):
  - `GET /public/export/screen` (`include=core` adds the core rows)
  - `GET /public/export/snapshot` (`date=YYYY-MM-DD` optional)
  - `GET /public/export/bars/{SYMBOL}?days=5`: 15-minute bars including premarket and after-hours, each tagged `PRE`/`REGULAR`/`POST`
- The earnings date is on every row.

## Run

```bash
mvn spring-boot:run                        # demo data (made-up DEMA..DEMI tickers), no network or keys
SCREENER_PROVIDER=yahoo ZOYA_API_KEY=... mvn spring-boot:run   # live
```

Open http://localhost:8080.

| Env var | Default | |
|---|---|---|
| `SCREENER_PROVIDER` | `demo` | `yahoo` for live Nasdaq Trader + Yahoo Finance data |
| `ZOYA_API_KEY` | – | Zoya GraphQL API key |
| `ZOYA_URL` | `https://api.zoya.finance/graphql` | use `https://sandbox-api.zoya.finance/graphql` for the sandbox |
| `ACCOUNT_SIZE` / `RISK_PERCENT` | 25000 / 1.0 | defaults for Analyze and the snapshot |
| `EXPORT_TOKEN` | – | if set, `/public/export/**` requires `?token=` |
| `SCREENER_SCHEDULE` | `false` | `true` runs the screen at 8:00 ET and refreshes the snapshot at 9:35 ET on weekdays |
| `SCREENER_STORE_DIR` | `./data` | Zoya cache, last screen, core stocks, snapshots |

## API

`POST /api/screen/run[?wait=true&refreshZoya=true]` · `GET /api/screen[?all=true]` · `GET /api/row/{sym}` · `GET /api/analyze/{sym}?account=&riskPercent=&side=long|short` · `GET /api/gex/{sym}` · `GET /api/zoya/{sym}` · `GET|POST|DELETE /api/core[/{sym}]` · `GET|POST /api/snapshot`

Yahoo Finance's endpoints are free and unofficial. They can lag or rate-limit you. This app is a screen, not investment advice.

## Open it in a browser (Azure)

Pushes to `main` or `claude/screener-app-rules-tz3n49` build the app and deploy it to Azure App Service with `.github/workflows/azure-webapp.yml`.

1. In the Azure portal, **Create a resource → Web App**: Publish **Code**, Runtime **Java 21**, Java web server **Java SE (Embedded Web Server)**, OS **Linux**. The Free F1 plan works for trying it out.
2. Open the web app's **Settings → Environment variables** and add:
   `APP_PASSWORD` (your sign-in password), `ZOYA_API_KEY`, `ZOYA_URL` (sandbox only), `SCREENER_PROVIDER=yahoo`, `SCREENER_STORE_DIR=/home/data`. Save.
3. On the web app's **Overview**, choose **Download publish profile**. (If it is greyed out: **Configuration → General settings → SCM Basic Auth Publishing → On**.)
4. In GitHub, open **Settings → Secrets and variables → Actions**:
   - **Secrets** tab: add `AZURE_WEBAPP_PUBLISH_PROFILE` and paste the whole file.
   - **Variables** tab: add `AZURE_WEBAPP_NAME` with the web app's name.
5. In **Actions → Deploy to Azure Web App**, click **Run workflow**, or re-run the latest run.
6. Open `https://<app-name>.azurewebsites.net`. Sign in with any user name and your `APP_PASSWORD`.

Set `APP_PASSWORD` whenever the app is public, because without it anyone with the link can run screens on your Zoya key. `/public/export/**` doesn't use the password; it's protected by `EXPORT_TOKEN` instead.
