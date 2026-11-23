package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Shariah compliance from Zoya's GraphQL API. */
@Component
public class ZoyaClient {

    public enum Status { COMPLIANT, NON_COMPLIANT, QUESTIONABLE, NOT_COVERED, NOT_CHECKED }

    public record Report(Status status, String reportDate) {}

    private static final String QUERY =
            "query Report($symbol: String!) { basicCompliance { report(symbol: $symbol) { symbol status reportDate } } }";

    private final Http http;
    private final ScreenerProperties props;

    public ZoyaClient(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    public boolean configured() { return !props.getZoya().getApiKey().isBlank(); }

    public Report check(String symbol) throws Exception {
        if (!configured()) return new Report(Status.NOT_CHECKED, null);
        Map<String, Object> body = Map.of("query", QUERY, "variables", Map.of("symbol", symbol));
        JsonNode root = http.postJson(props.getZoya().getUrl(), body,
                Map.of("Content-Type", "application/json", "Authorization", props.getZoya().getApiKey()));
        JsonNode report = root.path("data").path("basicCompliance").path("report");
        if (report.isMissingNode() || report.isNull()) return new Report(Status.NOT_COVERED, null);
        String s = report.path("status").asText("");
        Status st = switch (s.toUpperCase()) {
            case "COMPLIANT" -> Status.COMPLIANT;
            case "NON_COMPLIANT" -> Status.NON_COMPLIANT;
            case "QUESTIONABLE" -> Status.QUESTIONABLE;
            default -> Status.NOT_COVERED;
        };
        return new Report(st, Http.text(report, "reportDate"));
    }
}
