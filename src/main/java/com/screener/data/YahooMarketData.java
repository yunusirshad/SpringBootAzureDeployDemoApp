package com.screener.data;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.config.AppConfig;
import com.screener.model.Bar;
import com.screener.model.Fundamentals;
import com.screener.model.ListedSymbol;
import com.screener.model.OptionChain;
import com.screener.model.OptionContract;
import com.screener.model.Quote;

/**
 * Live data. The universe is Nasdaq Trader's symbol directories (Nasdaq plus NYSE from otherlisted).
 * Quotes, fundamentals, bars and option chains come from Yahoo Finance's public JSON endpoints, which are
 * free, unofficial and can lag or rate-limit. Quote, quoteSummary and options need Yahoo's cookie + crumb;
 * chart and fundamentals-timeseries do not.
 */
public class YahooMarketData implements MarketData {
    private static final Logger log = LoggerFactory.getLogger(YahooMarketData.class);
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36";
    private static final String Q1 = "https://query1.finance.yahoo.com";
    private static final String Q2 = "https://query2.finance.yahoo.com";
    private static final Pattern TICKER = Pattern.compile("^[A-Z]{1,5}$|^[A-Z]{1,4}\\.[A-Z]$");
    private static final Pattern NOT_COMMON = Pattern.compile(
            "(?i)\\b(warrants?|units?|rights?|notes?|debentures?|preferred|preference|ETN|% )\\b|\\d+(\\.\\d+)?%");

    private final ObjectMapper mapper;
    private final Clock clock;
    private final long pauseMillis;
    private final HttpClient http;
    private volatile String crumb;

    public YahooMarketData(ObjectMapper mapper, Clock clock, long pauseMillis) {
        this.mapper = mapper;
        this.clock = clock;
        this.pauseMillis = pauseMillis;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return "yahoo";
    }

    // ---------------------------------------------------------------- universe

    @Override
    public List<ListedSymbol> universe() {
        List<ListedSymbol> out = new ArrayList<>();
        try {
            String nasdaq = get("https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt");
            out.addAll(parseNasdaqListed(nasdaq));
            String other = get("https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt");
            out.addAll(parseOtherListed(other));
        } catch (IOException e) {
            throw new IllegalStateException("Could not load the Nasdaq Trader symbol lists: " + e.getMessage(), e);
        }
        return out;
    }

    /** Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares */
    static List<ListedSymbol> parseNasdaqListed(String text) {
        List<ListedSymbol> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] c = line.split("\\|", -1);
            if (c.length < 8 || c[0].equals("Symbol") || line.startsWith("File Creation")) continue;
            if ("Y".equals(c[3]) || "Y".equals(c[6]) || "Y".equals(c[7])) continue;
            if (keep(c[0], c[1])) out.add(new ListedSymbol(yahooSymbol(c[0]), c[1], "NASDAQ"));
        }
        return out;
    }

    /** ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol. Exchange N = NYSE. */
    static List<ListedSymbol> parseOtherListed(String text) {
        List<ListedSymbol> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String[] c = line.split("\\|", -1);
            if (c.length < 8 || c[0].equals("ACT Symbol") || line.startsWith("File Creation")) continue;
            if (!"N".equals(c[2]) || "Y".equals(c[4]) || "Y".equals(c[6])) continue;
            if (keep(c[0], c[1])) out.add(new ListedSymbol(yahooSymbol(c[0]), c[1], "NYSE"));
        }
        return out;
    }

    private static boolean keep(String symbol, String name) {
        return TICKER.matcher(symbol).matches() && !NOT_COMMON.matcher(name).find();
    }

    private static String yahooSymbol(String s) {
        return s.replace('.', '-');
    }

    // ---------------------------------------------------------------- quotes and fundamentals

    @Override
    public Map<String, Quote> quotes(List<String> symbols) {
        Map<String, Quote> out = new LinkedHashMap<>();
        for (int i = 0; i < symbols.size(); i += 100) {
            List<String> batch = symbols.subList(i, Math.min(symbols.size(), i + 100));
            try {
                JsonNode root = getJsonCrumb(Q2 + "/v7/finance/quote?symbols=" + enc(String.join(",", batch))
                        + "&fields=regularMarketPrice,marketCap,shortName,longName,fullExchangeName");
                for (JsonNode q : root.path("quoteResponse").path("result")) {
                    String sym = q.path("symbol").asText();
                    out.put(sym, new Quote(sym, text(q, "longName", text(q, "shortName", sym)),
                            q.path("fullExchangeName").asText(null), num(q.path("regularMarketPrice")),
                            num(q.path("marketCap"))));
                }
            } catch (Exception e) {
                log.warn("Quote batch at {} failed: {}", i, e.getMessage());
            }
            pause();
        }
        return out;
    }

    @Override
    public Fundamentals fundamentals(String symbol) {
        try {
            JsonNode res = getJsonCrumb(Q2 + "/v10/finance/quoteSummary/" + enc(symbol)
                    + "?modules=price,financialData,calendarEvents,defaultKeyStatistics")
                    .path("quoteSummary").path("result").path(0);
            if (res.isMissingNode()) throw new IllegalStateException("No quoteSummary for " + symbol);
            JsonNode price = res.path("price");
            JsonNode fd = res.path("financialData");
            Map<String, Double> bs = balanceSheet(symbol);

            Double totalDebt = first(raw(fd, "totalDebt"), bs.get("TotalDebt"));
            Double totalCash = first(raw(fd, "totalCash"), bs.get("CashCashEquivalentsAndShortTermInvestments"));
            Double px = first(raw(price, "regularMarketPrice"), raw(fd, "currentPrice"));
            LocalDate earnings = null;
            JsonNode ed = res.path("calendarEvents").path("earnings").path("earningsDate");
            if (ed.isArray() && !ed.isEmpty() && ed.get(0).has("raw")) {
                earnings = Instant.ofEpochSecond(ed.get(0).path("raw").asLong()).atZone(AppConfig.NEW_YORK).toLocalDate();
            }
            return new Fundamentals(symbol,
                    text(price, "longName", text(price, "shortName", symbol)),
                    price.path("exchangeName").asText(null),
                    px, raw(price, "marketCap"), raw(fd, "targetMeanPrice"),
                    totalDebt, totalCash, bs.get("TotalAssets"), bs.get("StockholdersEquity"),
                    raw(fd, "revenueGrowth"), earnings,
                    raw(fd, "grossMargins"), raw(fd, "operatingMargins"), raw(fd, "profitMargins"),
                    raw(fd, "returnOnEquity"), raw(fd, "freeCashflow"), raw(fd, "currentRatio"));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Fundamentals for " + symbol + " failed: " + e.getMessage(), e);
        } finally {
            pause();
        }
    }

    /** Latest quarterly balance sheet values from fundamentals-timeseries (no crumb needed). */
    private Map<String, Double> balanceSheet(String symbol) {
        Map<String, Double> out = new HashMap<>();
        String[] types = {"TotalAssets", "StockholdersEquity", "TotalDebt", "CashCashEquivalentsAndShortTermInvestments"};
        StringBuilder t = new StringBuilder();
        for (String s : types) t.append(t.isEmpty() ? "" : ",").append("quarterly").append(s);
        long now = clock.instant().getEpochSecond();
        try {
            JsonNode root = getJson(Q2 + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + enc(symbol)
                    + "?type=" + t + "&period1=" + (now - 400L * 86400) + "&period2=" + now);
            for (JsonNode r : root.path("timeseries").path("result")) {
                String type = r.path("meta").path("type").path(0).asText();
                JsonNode arr = r.path(type);
                Double latest = null;
                String latestDate = "";
                for (JsonNode v : arr) {
                    if (v.isNull()) continue;
                    String d = v.path("asOfDate").asText("");
                    if (d.compareTo(latestDate) >= 0 && v.path("reportedValue").has("raw")) {
                        latestDate = d;
                        latest = v.path("reportedValue").path("raw").asDouble();
                    }
                }
                if (latest != null) out.put(type.replaceFirst("^quarterly", ""), latest);
            }
        } catch (Exception e) {
            log.debug("Balance sheet for {} failed: {}", symbol, e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------- bars

    @Override
    public List<Bar> dailyBars(String symbol, int days) {
        String range = days <= 30 ? "3mo" : days <= 120 ? "6mo" : "1y";
        List<Bar> bars = chart(symbol, "1d", range, false, true);
        return bars.size() > days ? bars.subList(bars.size() - days, bars.size()) : bars;
    }

    @Override
    public List<Bar> intradayBars(String symbol, String interval, int days, boolean prePost) {
        return chart(symbol, interval, Math.max(1, Math.min(days, 60)) + "d", prePost, false);
    }

    private List<Bar> chart(String symbol, String interval, String range, boolean prePost, boolean daily) {
        try {
            JsonNode res = getJson(Q2 + "/v8/finance/chart/" + enc(symbol) + "?interval=" + interval + "&range=" + range
                    + "&includePrePost=" + prePost).path("chart").path("result").path(0);
            JsonNode ts = res.path("timestamp");
            JsonNode q = res.path("indicators").path("quote").path(0);
            List<Bar> out = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) {
                JsonNode c = q.path("close").path(i);
                if (c.isNull() || c.isMissingNode()) continue;
                Instant t = Instant.ofEpochSecond(ts.get(i).asLong());
                out.add(new Bar(t, q.path("open").path(i).asDouble(), q.path("high").path(i).asDouble(),
                        q.path("low").path(i).asDouble(), c.asDouble(), q.path("volume").path(i).asLong(),
                        daily ? "DAY" : Sessions.of(t)));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Chart for " + symbol + " failed: " + e.getMessage(), e);
        } finally {
            pause();
        }
    }

    // ---------------------------------------------------------------- options

    @Override
    public OptionChain optionChain(String symbol, int maxExpirations) {
        try {
            JsonNode first = getJsonCrumb(Q2 + "/v7/finance/options/" + enc(symbol)).path("optionChain").path("result").path(0);
            double spot = first.path("quote").path("regularMarketPrice").asDouble();
            List<OptionContract> contracts = new ArrayList<>();
            JsonNode exps = first.path("expirationDates");
            for (int i = 0; i < Math.min(maxExpirations, exps.size()); i++) {
                JsonNode chain = i == 0 ? first : getJsonCrumb(Q2 + "/v7/finance/options/" + enc(symbol) + "?date="
                        + exps.get(i).asLong()).path("optionChain").path("result").path(0);
                JsonNode opts = chain.path("options").path(0);
                addContracts(contracts, opts.path("calls"), true);
                addContracts(contracts, opts.path("puts"), false);
                pause();
            }
            return new OptionChain(symbol, spot, contracts);
        } catch (Exception e) {
            throw new IllegalStateException("Options for " + symbol + " failed: " + e.getMessage(), e);
        }
    }

    private static void addContracts(List<OptionContract> out, JsonNode arr, boolean call) {
        for (JsonNode o : arr) {
            LocalDate exp = Instant.ofEpochSecond(o.path("expiration").asLong()).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            out.add(new OptionContract(call, o.path("strike").asDouble(), exp, o.path("openInterest").asLong(0),
                    o.path("impliedVolatility").asDouble(0)));
        }
    }

    // ---------------------------------------------------------------- http

    private JsonNode getJsonCrumb(String url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            String c = crumb();
            String full = url + (url.contains("?") ? "&" : "?") + "crumb=" + enc(c);
            HttpResponse<String> r = send(full);
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                crumb = null;
                continue;
            }
            check(r, url);
            return mapper.readTree(r.body());
        }
        throw new IOException("Yahoo refused the crumb for " + url);
    }

    private synchronized String crumb() throws Exception {
        if (crumb != null) return crumb;
        send("https://fc.yahoo.com"); // sets the session cookie; the 404 body is expected
        HttpResponse<String> r = send(Q1 + "/v1/test/getcrumb");
        check(r, "getcrumb");
        String c = r.body().trim();
        if (c.isEmpty() || c.contains("<")) throw new IOException("Yahoo returned no crumb");
        crumb = c;
        return c;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpResponse<String> r = send(url);
        if (r.statusCode() >= 500 || r.statusCode() == 429) r = send(url.replace(Q2, Q1));
        check(r, url);
        return mapper.readTree(r.body());
    }

    private String get(String url) throws IOException {
        try {
            HttpResponse<String> r = send(url);
            check(r, url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private HttpResponse<String> send(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA).header("Accept", "application/json,text/plain,*/*").GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) {
            Thread.sleep(2000);
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        return r;
    }

    private static void check(HttpResponse<String> r, String what) throws IOException {
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + what);
    }

    private void pause() {
        if (pauseMillis <= 0) return;
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Double raw(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (n.isObject()) n = n.path("raw");
        return num(n);
    }

    private static Double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static String text(JsonNode n, String field, String dflt) {
        String v = n.path(field).asText("");
        return v.isBlank() ? dflt : v;
    }

    @SafeVarargs
    private static <T> T first(T... vals) {
        for (T v : vals) if (v != null) return v;
        return null;
    }
}
