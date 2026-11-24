package com.personalscreener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Everything a user would want to tune, bound from {@code screener.*} in application.yml or env vars. */
@ConfigurationProperties(prefix = "screener")
public class ScreenerProperties {

    /** Folder for watchlist, alerts, Zoya cache and the last snapshot. On Azure App Service use /home/data. */
    private String dataDir = "./data";
    private Filters filters = new Filters();
    private Quality quality = new Quality();
    private Zoya zoya = new Zoya();
    private Yahoo yahoo = new Yahoo();
    private Options options = new Options();
    private Notify notify = new Notify();
    private TradingView tradingview = new TradingView();
    private Sec sec = new Sec();
    /** Symbols to screen when TradingView cannot be reached, so the app still has a universe. */
    private List<String> fallbackUniverse = new ArrayList<>();
    /** How many screened names get the full bar-by-bar analysis. */
    private int maxAnalyzed = 60;

    public static class Filters {
        private List<String> exchanges = new ArrayList<>(List.of("NASDAQ", "NYSE"));
        private double priceMin = 1.5;
        private double priceMax = 20;
        private double marketCapMin = 300_000_000d;
        private double targetUpsideMin = 25;
        private double debtEquityMax = 0.5;
        private double debtAssetsMax = 0.3;
        private double debtCapitalMax = 0.3;
        private double cashDebtMin = 1;
        private double revenueGrowthMin = 5;

        public List<String> getExchanges() { return exchanges; }
        public void setExchanges(List<String> v) { exchanges = v; }
        public double getPriceMin() { return priceMin; }
        public void setPriceMin(double v) { priceMin = v; }
        public double getPriceMax() { return priceMax; }
        public void setPriceMax(double v) { priceMax = v; }
        public double getMarketCapMin() { return marketCapMin; }
        public void setMarketCapMin(double v) { marketCapMin = v; }
        public double getTargetUpsideMin() { return targetUpsideMin; }
        public void setTargetUpsideMin(double v) { targetUpsideMin = v; }
        public double getDebtEquityMax() { return debtEquityMax; }
        public void setDebtEquityMax(double v) { debtEquityMax = v; }
        public double getDebtAssetsMax() { return debtAssetsMax; }
        public void setDebtAssetsMax(double v) { debtAssetsMax = v; }
        public double getDebtCapitalMax() { return debtCapitalMax; }
        public void setDebtCapitalMax(double v) { debtCapitalMax = v; }
        public double getCashDebtMin() { return cashDebtMin; }
        public void setCashDebtMin(double v) { cashDebtMin = v; }
        public double getRevenueGrowthMin() { return revenueGrowthMin; }
        public void setRevenueGrowthMin(double v) { revenueGrowthMin = v; }
    }

    public static class Quality {
        private int passScore = 60;
        private double minDollarVolume = 2_000_000;
        private double maxSpreadBps = 50;
        private double relVolMin = 0.5, relVolMax = 10;
        private double dollarVolMin = 500_000, dollarVolMax = 20_000_000;
        private double spreadMin = 5, spreadMax = 80;
        private double floatMin = 2_000_000, floatMax = 50_000_000;
        private double atrPctMin = 3, atrPctMax = 12;

        public int getPassScore() { return passScore; }
        public void setPassScore(int v) { passScore = v; }
        public double getMinDollarVolume() { return minDollarVolume; }
        public void setMinDollarVolume(double v) { minDollarVolume = v; }
        public double getMaxSpreadBps() { return maxSpreadBps; }
        public void setMaxSpreadBps(double v) { maxSpreadBps = v; }
        public double getRelVolMin() { return relVolMin; }
        public void setRelVolMin(double v) { relVolMin = v; }
        public double getRelVolMax() { return relVolMax; }
        public void setRelVolMax(double v) { relVolMax = v; }
        public double getDollarVolMin() { return dollarVolMin; }
        public void setDollarVolMin(double v) { dollarVolMin = v; }
        public double getDollarVolMax() { return dollarVolMax; }
        public void setDollarVolMax(double v) { dollarVolMax = v; }
        public double getSpreadMin() { return spreadMin; }
        public void setSpreadMin(double v) { spreadMin = v; }
        public double getSpreadMax() { return spreadMax; }
        public void setSpreadMax(double v) { spreadMax = v; }
        public double getFloatMin() { return floatMin; }
        public void setFloatMin(double v) { floatMin = v; }
        public double getFloatMax() { return floatMax; }
        public void setFloatMax(double v) { floatMax = v; }
        public double getAtrPctMin() { return atrPctMin; }
        public void setAtrPctMin(double v) { atrPctMin = v; }
        public double getAtrPctMax() { return atrPctMax; }
        public void setAtrPctMax(double v) { atrPctMax = v; }
    }

    public static class Zoya {
        /** "page" reads https://zoya.finance/stocks/{ticker} (no key needed); "api" uses the GraphQL API with apiKey. */
        private String source = "page";
        /** The stock page; the lower-case ticker is appended. */
        private String pageUrl = "https://zoya.finance/stocks/";
        /** Zoya API key, used only with source "api". */
        private String apiKey = "";
        private String url = "https://api.zoya.finance/graphql";
        /** Re-check a name after this many hours. */
        private int cacheHours = 24;
        /** A status whose last successful check is older than this many days is shown as Stale and kept off Core. */
        private int staleDays = 7;

        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public String getPageUrl() { return pageUrl; }
        public void setPageUrl(String v) { pageUrl = v; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { apiKey = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public int getCacheHours() { return cacheHours; }
        public void setCacheHours(int v) { cacheHours = v; }
        public int getStaleDays() { return staleDays; }
        public void setStaleDays(int v) { staleDays = v; }
    }

    /** Phone alerts. Each channel is on only when its settings are filled in. */
    public static class Notify {
        /** Telegram: a bot token from @BotFather and your chat id. */
        private String telegramToken = "";
        private String telegramChatId = "";
        /** ntfy: a topic name (subscribe to it in the ntfy app); the server defaults to ntfy.sh. */
        private String ntfyTopic = "";
        private String ntfyServer = "https://ntfy.sh";

        public String getTelegramToken() { return telegramToken; }
        public void setTelegramToken(String v) { telegramToken = v; }
        public String getTelegramChatId() { return telegramChatId; }
        public void setTelegramChatId(String v) { telegramChatId = v; }
        public String getNtfyTopic() { return ntfyTopic; }
        public void setNtfyTopic(String v) { ntfyTopic = v; }
        public String getNtfyServer() { return ntfyServer; }
        public void setNtfyServer(String v) { ntfyServer = v; }
        public boolean telegram() { return !telegramToken.isBlank() && !telegramChatId.isBlank(); }
        public boolean ntfy() { return !ntfyTopic.isBlank(); }
    }

    public static class Options {
        /** "cboe" (default): Cboe's delayed quotes, with Yahoo as the fallback. "yahoo": Yahoo only. */
        private String source = "cboe";
        /** The symbol is appended, then ".json". */
        private String cboeUrl = "https://cdn.cboe.com/api/global/delayed_quotes/options/";

        public String getSource() { return source; }
        public void setSource(String v) { source = v; }
        public String getCboeUrl() { return cboeUrl; }
        public void setCboeUrl(String v) { cboeUrl = v; }
    }

    public static class Yahoo {
        /** Use Yahoo's cookie + crumb session for batch quotes (bid/ask) and option chains. */
        private boolean crumbEnabled = true;
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

        public boolean isCrumbEnabled() { return crumbEnabled; }
        public void setCrumbEnabled(boolean v) { crumbEnabled = v; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String v) { userAgent = v; }
    }

    public static class TradingView {
        private String scanUrl = "https://scanner.tradingview.com/america/scan";
        private int maxRows = 500;

        public String getScanUrl() { return scanUrl; }
        public void setScanUrl(String v) { scanUrl = v; }
        public int getMaxRows() { return maxRows; }
        public void setMaxRows(int v) { maxRows = v; }
    }

    public static class Sec {
        /** SEC asks for a descriptive User-Agent with a contact address. Set SCREENER_SEC_USER_AGENT. */
        private String userAgent = "PersonalScreener/2026 contact@example.com";

        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String v) { userAgent = v; }
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String v) { dataDir = v; }
    public Filters getFilters() { return filters; }
    public void setFilters(Filters v) { filters = v; }
    public Quality getQuality() { return quality; }
    public void setQuality(Quality v) { quality = v; }
    public Zoya getZoya() { return zoya; }
    public void setZoya(Zoya v) { zoya = v; }
    public Yahoo getYahoo() { return yahoo; }
    public Options getOptions() { return options; }
    public Notify getNotify() { return notify; }
    public void setNotify(Notify v) { notify = v; }
    public void setOptions(Options v) { options = v; }
    public void setYahoo(Yahoo v) { yahoo = v; }
    public TradingView getTradingview() { return tradingview; }
    public void setTradingview(TradingView v) { tradingview = v; }
    public Sec getSec() { return sec; }
    public void setSec(Sec v) { sec = v; }
    public List<String> getFallbackUniverse() { return fallbackUniverse; }
    public void setFallbackUniverse(List<String> v) { fallbackUniverse = v; }
    public int getMaxAnalyzed() { return maxAnalyzed; }
    public void setMaxAnalyzed(int v) { maxAnalyzed = v; }
}
