package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Free, delayed Yahoo Finance data. The chart endpoint needs no session; batch quotes (bid/ask),
 * option chains and earnings dates need Yahoo's cookie + crumb, which is fetched once and reused.
 */
@Component
public class YahooClient {

    private static final Logger log = LoggerFactory.getLogger(YahooClient.class);
    private static final String BASE = "https://query1.finance.yahoo.com";

    private final Http http;
    private final ScreenerProperties props;
    private volatile String crumb;
    private volatile Instant crumbFailedAt;
    /** Why the last crumb or option-chain call failed, for the status line; null when fine. */
    private volatile String lastError;
    private volatile Instant crumbAt;

    public YahooClient(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    private Map<String, String> headers() {
        return Map.of("User-Agent", props.getYahoo().getUserAgent(), "Accept", "application/json");
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Candles plus the chart's meta block. */
    public record Chart(List<Bar> bars, JsonNode meta) {}

    public Chart chart(String symbol, String interval, String range, boolean prePost) throws IOException, InterruptedException {
        String url = BASE + "/v8/finance/chart/" + enc(symbol) + "?interval=" + interval + "&range=" + range
                + "&includePrePost=" + prePost;
        JsonNode root = http.getJson(url, headers());
        JsonNode result = root.path("chart").path("result").path(0);
        if (result.isMissingNode()) throw new IOException("No chart for " + symbol);
        JsonNode ts = result.path("timestamp");
        JsonNode q = result.path("indicators").path("quote").path(0);
        List<Bar> bars = new ArrayList<>(ts.size());
        for (int i = 0; i < ts.size(); i++) {
            double o = Http.num(q.path("open").get(i)), h = Http.num(q.path("high").get(i)),
                    l = Http.num(q.path("low").get(i)), c = Http.num(q.path("close").get(i));
            double v = Http.num(q.path("volume").get(i));
            if (Double.isNaN(c) || Double.isNaN(h) || Double.isNaN(l)) continue;
            bars.add(new Bar(ts.get(i).asLong(), Double.isNaN(o) ? c : o, h, l, c, Double.isNaN(v) ? 0 : v));
        }
        return new Chart(bars, result.path("meta"));
    }

    public List<Bar> bars(String symbol, String interval, String range) throws IOException, InterruptedException {
        return chart(symbol, interval, range, false).bars();
    }

    /**
     * Quote from today's 5-minute chart with extended hours. Gives price, previous close, day range,
     * premarket high and volume without a crumb. No bid/ask.
     */
    public Quote chartQuote(String symbol) throws IOException, InterruptedException {
        Chart ch = chart(symbol, "5m", "1d", true);
        JsonNode m = ch.meta();
        long regStart = m.path("currentTradingPeriod").path("regular").path("start").asLong(0);
        double preHigh = Double.NaN;
        for (Bar b : ch.bars()) {
            if (regStart > 0 && b.time() < regStart) preHigh = Double.isNaN(preHigh) ? b.high() : Math.max(preHigh, b.high());
        }
        double prev = Http.num(m, "chartPreviousClose");
        if (Double.isNaN(prev)) prev = Http.num(m, "previousClose");
        return new Quote(symbol, Http.num(m, "regularMarketPrice"), prev,
                Http.num(m, "regularMarketDayHigh"), Http.num(m, "regularMarketDayLow"),
                Http.num(m, "regularMarketVolume"), Double.NaN, Double.NaN, preHigh,
                m.path("regularMarketTime").asLong(0));
    }

    // ---- crumb-backed endpoints -------------------------------------------------------------

    /**
     * Yahoo's cookie + crumb session, needed for option chains, bid/ask and earnings dates. Gets the session cookie
     * from fc.yahoo.com (a 404 there is normal), then from a finance.yahoo.com page if that did not work, and asks
     * for the crumb after each. A failure is logged with its reason and retried after 5 minutes.
     */
    private synchronized String crumb() throws InterruptedException {
        if (!props.getYahoo().isCrumbEnabled()) {
            lastError = "Option chains are off: SCREENER_YAHOO_CRUMB is false. Start the app without it to load them.";
            return null;
        }
        if (crumb != null) return crumb;
        if (crumbFailedAt != null && crumbFailedAt.isAfter(Instant.now().minus(Duration.ofMinutes(5)))) return null;
        String why = null;
        for (String cookieUrl : List.of("https://fc.yahoo.com", "https://finance.yahoo.com/quote/SPY/")) {
            try {
                http.get(cookieUrl, Map.of("User-Agent", props.getYahoo().getUserAgent(), "Accept", "text/html,*/*"));
            } catch (IOException e) {
                why = "could not reach " + Http.host(cookieUrl) + " (" + e.getMessage() + ")";
            }
            try {
                HttpResponse<String> r = http.get(BASE + "/v1/test/getcrumb",
                        Map.of("User-Agent", props.getYahoo().getUserAgent(), "Accept", "text/plain,*/*"));
                String body = r.body() == null ? "" : r.body().trim();
                if (r.statusCode() == 200 && !body.isEmpty() && !body.startsWith("{") && !body.startsWith("<") && body.length() < 40) {
                    crumb = body;
                    crumbAt = Instant.now();
                    crumbFailedAt = null;
                    lastError = null;
                    log.info("Yahoo session ready (cookie from {})", Http.host(cookieUrl));
                    return crumb;
                }
                why = "getcrumb answered HTTP " + r.statusCode() + (r.statusCode() == 429 ? " (rate limited)" : "")
                        + (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(80, body.length())));
            } catch (IOException e) {
                why = "getcrumb failed (" + e.getMessage() + ")";
            }
        }
        crumbFailedAt = Instant.now();
        lastError = "Yahoo session failed: " + why + ". Retrying in 5 minutes.";
        log.warn(lastError);
        return null;
    }

    /** For /api/chains/status: is the session up, and the last error. */
    public Map<String, Object> sessionStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crumbEnabled", props.getYahoo().isCrumbEnabled());
        m.put("sessionReady", crumb != null);
        m.put("sessionSince", crumbAt == null ? null : crumbAt.toString());
        m.put("lastFailureAt", crumbFailedAt == null ? null : crumbFailedAt.toString());
        m.put("lastError", lastError);
        return m;
    }

    public String lastError() { return lastError; }

    private JsonNode crumbGet(String pathAndQuery) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String c = crumb();
            if (c == null) return null;
            String sep = pathAndQuery.contains("?") ? "&" : "?";
            HttpResponse<String> r = http.get(BASE + pathAndQuery + sep + "crumb=" + enc(c), headers());
            if (r.statusCode() == 401 || r.statusCode() == 403) {   // the crumb went stale: get a new one once
                synchronized (this) { crumb = null; }
                lastError = "Yahoo rejected the session (HTTP " + r.statusCode() + "); renewing it";
                continue;
            }
            if (r.statusCode() / 100 != 2) {
                lastError = "Yahoo answered HTTP " + r.statusCode() + (r.statusCode() == 429 ? " (rate limited)" : "")
                        + " for " + pathAndQuery.replaceAll("\\?.*", "");
                log.warn(lastError);
                return null;
            }
            return http.mapper().readTree(r.body());
        }
        return null;
    }

    /** Batch quotes with bid/ask. Empty map when the crumb session is not available. */
    public Map<String, Quote> batchQuotes(Collection<String> symbols) {
        Map<String, Quote> out = new HashMap<>();
        List<String> list = new ArrayList<>(symbols);
        for (int i = 0; i < list.size(); i += 50) {
            String joined = String.join(",", list.subList(i, Math.min(list.size(), i + 50)));
            try {
                JsonNode root = crumbGet("/v7/finance/quote?symbols=" + enc(joined));
                if (root == null) return out;
                for (JsonNode q : root.path("quoteResponse").path("result")) {
                    String s = Http.text(q, "symbol");
                    double preHigh = Double.NaN; // v7 has premarket price only, not its high
                    out.put(s, new Quote(s, Http.num(q, "regularMarketPrice"), Http.num(q, "regularMarketPreviousClose"),
                            Http.num(q, "regularMarketDayHigh"), Http.num(q, "regularMarketDayLow"),
                            Http.num(q, "regularMarketVolume"), Http.num(q, "bid"), Http.num(q, "ask"),
                            preHigh, q.path("regularMarketTime").asLong(0)));
                }
            } catch (Exception e) {
                log.debug("Yahoo batch quote failed: {}", e.getMessage());
            }
        }
        return out;
    }

    /** Nearest listed expiry with open interest by strike, or null. */
    public OptionChain optionChain(String symbol) {
        List<OptionChain> all = optionChains(symbol, 0, 1);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every listed expiry from today out to {@code maxDays} days (at least the nearest one), up to
     * {@code maxExpiries}, each with open interest by strike. Empty when the chain cannot be read.
     */
    public List<OptionChain> optionChains(String symbol, int maxDays, int maxExpiries) {
        return chainResult(symbol, maxDays, maxExpiries).chains();
    }

    /**
     * Option chains plus whether Yahoo actually answered. {@code answered} is false when the session or the request
     * failed (worth retrying soon); true with no chains means the ticker has no listed options.
     */
    public record ChainResult(List<OptionChain> chains, boolean answered, String error) {}

    public ChainResult chainResult(String symbol, int maxDays, int maxExpiries) {
        List<OptionChain> out = new ArrayList<>();
        try {
            JsonNode root = crumbGet("/v7/finance/options/" + enc(symbol));
            if (root == null) return new ChainResult(out, false, lastError);
            JsonNode res0 = root.path("optionChain").path("result").path(0);
            if (res0.isMissingNode() || !res0.path("options").path(0).has("expirationDate")) {
                return new ChainResult(out, true, "no listed options");
            }
            JsonNode res = root.path("optionChain").path("result").path(0);
            double spot = Http.num(res.path("quote"), "regularMarketPrice");
            JsonNode first = res.path("options").path(0);
            long firstExp = first.get("expirationDate").asLong();
            out.add(chain(symbol, spot, first));
            long limit = Instant.now().getEpochSecond() + maxDays * 86_400L;
            for (JsonNode d : res.path("expirationDates")) {
                long exp = d.asLong();
                if (exp == firstExp) continue;
                if (out.size() >= maxExpiries || exp > limit) break;
                JsonNode more = crumbGet("/v7/finance/options/" + enc(symbol) + "?date=" + exp);
                if (more == null) break;
                JsonNode opt = more.path("optionChain").path("result").path(0).path("options").path(0);
                if (opt.has("expirationDate")) out.add(chain(symbol, spot, opt));
            }
        } catch (Exception e) {
            log.warn("Yahoo options failed for {}: {}", symbol, e.getMessage());
            return new ChainResult(out, !out.isEmpty(), e.getMessage());
        }
        return new ChainResult(out, true, null);
    }

    private static OptionChain chain(String symbol, double spot, JsonNode opt) {
        LocalDate exp = Instant.ofEpochSecond(opt.get("expirationDate").asLong()).atZone(ZoneOffset.UTC).toLocalDate();
        return new OptionChain(symbol, exp, spot, strikes(opt.path("calls")), strikes(opt.path("puts")));
    }

    private static List<OptionChain.Strike> strikes(JsonNode arr) {
        List<OptionChain.Strike> out = new ArrayList<>();
        for (JsonNode n : arr) {
            double oi = Http.num(n, "openInterest");
            out.add(new OptionChain.Strike(Http.num(n, "strike"), Double.isNaN(oi) ? 0 : oi, Http.num(n, "impliedVolatility")));
        }
        return out;
    }

    /** Next earnings date from quoteSummary calendarEvents, or null. */
    public LocalDate nextEarnings(String symbol) {
        try {
            JsonNode root = crumbGet("/v10/finance/quoteSummary/" + enc(symbol) + "?modules=calendarEvents");
            if (root == null) return null;
            JsonNode dates = root.path("quoteSummary").path("result").path(0).path("calendarEvents")
                    .path("earnings").path("earningsDate");
            for (JsonNode d : dates) {
                long raw = d.path("raw").asLong(0);
                if (raw > 0) return Instant.ofEpochSecond(raw).atZone(ZoneId.of("America/New_York")).toLocalDate();
            }
        } catch (Exception e) {
            log.debug("Yahoo earnings failed for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    public boolean crumbAvailable() { return crumb != null; }
}
