package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SEC EDGAR XBRL company facts, for the Analyze tab. */
@Component
public class SecClient {

    private final Http http;
    private final ScreenerProperties props;
    private volatile Map<String, String> tickerToCik;

    public SecClient(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    private Map<String, String> headers() {
        return Map.of("User-Agent", props.getSec().getUserAgent(), "Accept", "application/json");
    }

    public String cik(String ticker) throws IOException, InterruptedException {
        if (tickerToCik == null) {
            JsonNode all = http.getJson("https://www.sec.gov/files/company_tickers.json", headers());
            Map<String, String> m = new ConcurrentHashMap<>();
            all.forEach(n -> m.put(n.path("ticker").asText().toUpperCase(),
                    String.format("%010d", n.path("cik_str").asLong())));
            tickerToCik = m;
        }
        return tickerToCik.get(ticker.toUpperCase());
    }

    public JsonNode companyFacts(String cik) throws IOException, InterruptedException {
        return http.getJson("https://data.sec.gov/api/xbrl/companyfacts/CIK" + cik + ".json", headers());
    }
}
