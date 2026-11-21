package com.screener.data;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.config.ScreenerProperties;
import com.screener.model.ZoyaCheck;

/**
 * Zoya's GraphQL API (basicCompliance.report). The key comes from ZOYA_API_KEY; for Zoya's sandbox set
 * ZOYA_URL=https://sandbox-api.zoya.finance/graphql.
 */
public class ZoyaGraphqlClient implements ZoyaClient {

    static final String QUERY = """
            query GetReport($symbol: String!) {
              basicCompliance {
                report(symbol: $symbol) { symbol name status reportDate }
              }
            }""";

    private final ScreenerProperties.Zoya props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;

    public ZoyaGraphqlClient(ScreenerProperties.Zoya props, ObjectMapper mapper, Clock clock) {
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds())).build();
    }

    @Override
    public ZoyaCheck check(String symbol) throws Exception {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("ZOYA_API_KEY is not set");
        }
        String body = mapper.writeValueAsString(Map.of("query", QUERY, "variables", Map.of("symbol", symbol)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getUrl()))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Zoya HTTP " + resp.statusCode());
        }
        return parse(symbol, mapper.readTree(resp.body()));
    }

    ZoyaCheck parse(String symbol, JsonNode root) {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Zoya error: " + errors.get(0).path("message").asText());
        }
        JsonNode report = root.path("data").path("basicCompliance").path("report");
        String status = report.path("status").asText("");
        if (report.isMissingNode() || report.isNull() || status.isBlank()) {
            throw new IllegalStateException("Zoya has no report for " + symbol);
        }
        return new ZoyaCheck(symbol, status.toUpperCase(), clock.instant(), "LIVE", null);
    }
}
