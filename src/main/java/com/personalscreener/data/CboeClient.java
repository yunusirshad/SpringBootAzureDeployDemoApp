package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Option chains from Cboe's free delayed quotes: {@code https://cdn.cboe.com/api/global/delayed_quotes/options/MITK.json}.
 * One file holds every listed expiry; each contract is named in OCC form (root, YYMMDD, C or P, strike x 1000 in
 * 8 digits) with its open interest and implied volatility. No session or key is needed. Quotes are delayed about
 * 15 minutes; open interest updates once a day before the open.
 */
@Component
public class CboeClient {

    private static final Logger log = LoggerFactory.getLogger(CboeClient.class);
    private static final Pattern OCC = Pattern.compile("^(.+?)(\\d{6})([CP])(\\d{8})$");

    private final Http http;
    private final ScreenerProperties props;
    private volatile String lastError;

    public CboeClient(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    public String lastError() { return lastError; }

    /** Same contract as {@link YahooClient#chainResult}: answered=false means the request failed. */
    public YahooClient.ChainResult chainResult(String symbol, int maxDays, int maxExpiries, LocalDate today) {
        String url = props.getOptions().getCboeUrl() + symbol.toUpperCase(Locale.ROOT).replace("^", "_") + ".json";
        try {
            HttpResponse<String> r = http.get(url, Map.of("Accept", "application/json",
                    "User-Agent", props.getYahoo().getUserAgent()));
            if (r.statusCode() == 404) return new YahooClient.ChainResult(List.of(), true, "no listed options on Cboe");
            if (r.statusCode() / 100 != 2) {
                lastError = "Cboe answered HTTP " + r.statusCode() + (r.statusCode() == 403 ? " (blocked from this network)" : "")
                        + (r.statusCode() == 429 ? " (rate limited)" : "");
                return new YahooClient.ChainResult(List.of(), false, lastError);
            }
            List<OptionChain> chains = parse(symbol, http.mapper().readTree(r.body()), today, maxDays, maxExpiries);
            lastError = null;
            return new YahooClient.ChainResult(chains, true, chains.isEmpty() ? "no listed options on Cboe" : null);
        } catch (Exception e) {
            lastError = "Cboe request failed (" + e.getMessage() + ")";
            log.warn("Cboe options failed for {}: {}", symbol, e.getMessage());
            return new YahooClient.ChainResult(List.of(), false, lastError);
        }
    }

    /**
     * Expiries from today out to {@code maxDays} (at least the nearest), at most {@code maxExpiries}, each with open
     * interest and IV by strike.
     */
    public static List<OptionChain> parse(String symbol, JsonNode root, LocalDate today, int maxDays, int maxExpiries) {
        JsonNode data = root.path("data");
        double spot = Http.num(data, "current_price");
        if (Double.isNaN(spot)) spot = Http.num(data, "close");
        Map<LocalDate, List<OptionChain.Strike>> calls = new TreeMap<>(), puts = new TreeMap<>();
        for (JsonNode o : data.path("options")) {
            Matcher m = OCC.matcher(o.path("option").asText(""));
            if (!m.matches()) continue;
            String ymd = m.group(2);
            LocalDate exp;
            try {
                exp = LocalDate.of(2000 + Integer.parseInt(ymd.substring(0, 2)), Integer.parseInt(ymd.substring(2, 4)),
                        Integer.parseInt(ymd.substring(4, 6)));
            } catch (Exception e) { continue; }
            if (exp.isBefore(today)) continue;
            double strike = Long.parseLong(m.group(4)) / 1000.0;
            double oi = Http.num(o, "open_interest");
            double iv = Http.num(o, "iv");
            OptionChain.Strike k = new OptionChain.Strike(strike, Double.isNaN(oi) ? 0 : oi, iv);
            (m.group(3).equals("C") ? calls : puts).computeIfAbsent(exp, x -> new ArrayList<>()).add(k);
        }
        TreeSet<LocalDate> expiries = new TreeSet<>(calls.keySet());
        expiries.addAll(puts.keySet());
        List<OptionChain> out = new ArrayList<>();
        LocalDate limit = today.plusDays(maxDays);
        for (LocalDate e : expiries) {
            if (out.size() >= maxExpiries || (!out.isEmpty() && e.isAfter(limit))) break;
            out.add(new OptionChain(symbol, e, spot, calls.getOrDefault(e, List.of()), puts.getOrDefault(e, List.of())));
        }
        return out;
    }
}
