package com.screener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every number the screen, the Zoya cache, the planner and the exports use. Set in application.yml or env vars. */
@ConfigurationProperties(prefix = "screener")
public class ScreenerProperties {

    private final Rules rules = new Rules();
    private final Zoya zoya = new Zoya();
    private final Account account = new Account();
    private final Data data = new Data();
    private final Export export = new Export();
    private final Schedule schedule = new Schedule();
    private String storeDir = "./data";

    public Rules getRules() { return rules; }
    public Zoya getZoya() { return zoya; }
    public Account getAccount() { return account; }
    public Data getData() { return data; }
    public Export getExport() { return export; }
    public Schedule getSchedule() { return schedule; }
    public String getStoreDir() { return storeDir; }
    public void setStoreDir(String storeDir) { this.storeDir = storeDir; }

    /** The gates. Every one of these removes a name; quality never does. */
    public static class Rules {
        private double minPrice = 1.50;
        private double maxPrice = 20.00;
        private double minMarketCap = 300_000_000d;
        private double minUpside = 0.25;
        private double maxDebtEquity = 0.5;
        private double maxDebtAssets = 0.3;
        private double maxDebtCapital = 0.3;
        private double minCashDebt = 1.0;
        private double minRevenueGrowth = 0.05;
        private boolean requireZoyaCompliant = true;

        public double getMinPrice() { return minPrice; }
        public void setMinPrice(double v) { minPrice = v; }
        public double getMaxPrice() { return maxPrice; }
        public void setMaxPrice(double v) { maxPrice = v; }
        public double getMinMarketCap() { return minMarketCap; }
        public void setMinMarketCap(double v) { minMarketCap = v; }
        public double getMinUpside() { return minUpside; }
        public void setMinUpside(double v) { minUpside = v; }
        public double getMaxDebtEquity() { return maxDebtEquity; }
        public void setMaxDebtEquity(double v) { maxDebtEquity = v; }
        public double getMaxDebtAssets() { return maxDebtAssets; }
        public void setMaxDebtAssets(double v) { maxDebtAssets = v; }
        public double getMaxDebtCapital() { return maxDebtCapital; }
        public void setMaxDebtCapital(double v) { maxDebtCapital = v; }
        public double getMinCashDebt() { return minCashDebt; }
        public void setMinCashDebt(double v) { minCashDebt = v; }
        public double getMinRevenueGrowth() { return minRevenueGrowth; }
        public void setMinRevenueGrowth(double v) { minRevenueGrowth = v; }
        public boolean isRequireZoyaCompliant() { return requireZoyaCompliant; }
        public void setRequireZoyaCompliant(boolean v) { requireZoyaCompliant = v; }
    }

    public static class Zoya {
        private String apiKey = "";
        private String url = "https://api.zoya.finance/graphql";
        private int cacheHours = 24;
        private int timeoutSeconds = 10;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { apiKey = v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url = v; }
        public int getCacheHours() { return cacheHours; }
        public void setCacheHours(int v) { cacheHours = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    }

    public static class Account {
        private double size = 25_000;
        private double riskPercent = 1.0;

        public double getSize() { return size; }
        public void setSize(double v) { size = v; }
        public double getRiskPercent() { return riskPercent; }
        public void setRiskPercent(double v) { riskPercent = v; }
    }

    public static class Data {
        /** yahoo = live (Nasdaq Trader universe + Yahoo Finance); demo = built-in sample data, no network. */
        private String provider = "demo";
        private int threads = 6;
        private int maxCandidates = 1500;
        private int gexExpirations = 4;
        private long pauseMillis = 150;

        public String getProvider() { return provider; }
        public void setProvider(String v) { provider = v; }
        public int getThreads() { return threads; }
        public void setThreads(int v) { threads = v; }
        public int getMaxCandidates() { return maxCandidates; }
        public void setMaxCandidates(int v) { maxCandidates = v; }
        public int getGexExpirations() { return gexExpirations; }
        public void setGexExpirations(int v) { gexExpirations = v; }
        public long getPauseMillis() { return pauseMillis; }
        public void setPauseMillis(long v) { pauseMillis = v; }
    }

    public static class Export {
        /** Empty = open to anyone. Set it to require ?token= on /public/export/**. */
        private String token = "";

        public String getToken() { return token; }
        public void setToken(String v) { token = v; }
    }

    public static class Schedule {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
    }
}
