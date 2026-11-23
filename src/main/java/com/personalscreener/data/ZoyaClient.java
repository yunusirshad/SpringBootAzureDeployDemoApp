package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shariah compliance from Zoya.
 *
 * <p>By default it reads the public stock page, {@code https://zoya.finance/stocks/mitk}, which states the
 * status in a line like "MITK stock is Shariah-compliant" or "JPM stock is not Shariah-compliant", with an
 * "As of November 23, 2026" date. A 404 means Zoya does not cover the ticker. With
 * {@code screener.zoya.source=api} and an API key it uses Zoya's GraphQL API instead.
 */
@Component
public class ZoyaClient {

    public enum Status { COMPLIANT, NON_COMPLIANT, QUESTIONABLE, NOT_COVERED, NOT_CHECKED }

    public record Report(Status status, String reportDate, String source) {}

    private static final String QUERY =
            "query Report($symbol: String!) { basicCompliance { report(symbol: $symbol) { symbol status reportDate } } }";
    private static final Pattern AS_OF = Pattern.compile("As of\\s+([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final Http http;
    private final ScreenerProperties props;

    public ZoyaClient(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    private boolean useApi() {
        return "api".equalsIgnoreCase(props.getZoya().getSource()) && !props.getZoya().getApiKey().isBlank();
    }

    /** The page needs no key, so this is false only when the API is chosen without one. */
    public boolean configured() {
        return !"api".equalsIgnoreCase(props.getZoya().getSource()) || !props.getZoya().getApiKey().isBlank();
    }

    public Report check(String symbol) throws Exception {
        if (!configured()) return new Report(Status.NOT_CHECKED, null, null);
        return useApi() ? checkApi(symbol) : checkPage(symbol);
    }

    Report checkPage(String symbol) throws IOException, InterruptedException {
        String url = props.getZoya().getPageUrl() + symbol.toLowerCase(Locale.ROOT);
        HttpResponse<String> r = http.get(url, Map.of(
                "User-Agent", props.getYahoo().getUserAgent(),
                "Accept", "text/html,application/xhtml+xml",
                "Accept-Language", "en-US,en;q=0.9"));
        if (r.statusCode() == 404) return new Report(Status.NOT_COVERED, null, "page");
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from zoya.finance");
        Report rep = parsePage(symbol, r.body());
        if (rep == null) throw new IOException("No compliance status found on the Zoya page for " + symbol);
        return rep;
    }

    /** Reads the status from a Zoya stock page; null when the page has no recognisable status. */
    public static Report parsePage(String symbol, String html) {
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&#160;", " ").replace(" ", " ")
                .replaceAll("\\s+", " ");

        // 1. the headline: "MITK stock is Shariah-compliant"
        Matcher m = Pattern.compile("\\b" + Pattern.quote(symbol) + " stock is ([A-Za-z\\- ]{3,40})",
                Pattern.CASE_INSENSITIVE).matcher(text);
        Status st = m.find() ? classify(m.group(1)) : null;
        // 2. the summary: "As of November 23, 2026, MITK is not Shariah-compliant"
        if (st == null) {
            Matcher s = Pattern.compile("\\(?\\b" + Pattern.quote(symbol) + "\\)? is ([A-Za-z\\- ]{3,40})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            while (st == null && s.find()) st = classify(s.group(1));
        }
        if (st == null) return null;

        String date = null;
        Matcher d = AS_OF.matcher(text);
        if (d.find()) {
            try { date = LocalDate.parse(d.group(1), LONG_DATE).toString(); } catch (Exception ignored) { /* leave null */ }
        }
        return new Report(st, date, "page");
    }

    static Status classify(String phrase) {
        String p = phrase.toLowerCase(Locale.ROOT).trim();
        if (p.startsWith("not shariah") || p.startsWith("non-compliant") || p.startsWith("not halal")) return Status.NON_COMPLIANT;
        if (p.startsWith("questionable") || p.startsWith("doubtful")) return Status.QUESTIONABLE;
        if (p.startsWith("shariah-compliant") || p.startsWith("shariah compliant") || p.startsWith("halal")) return Status.COMPLIANT;
        return null;
    }

    Report checkApi(String symbol) throws Exception {
        Map<String, Object> body = Map.of("query", QUERY, "variables", Map.of("symbol", symbol));
        JsonNode root = http.postJson(props.getZoya().getUrl(), body,
                Map.of("Content-Type", "application/json", "Authorization", props.getZoya().getApiKey()));
        JsonNode report = root.path("data").path("basicCompliance").path("report");
        if (report.isMissingNode() || report.isNull()) return new Report(Status.NOT_COVERED, null, "api");
        String s = report.path("status").asText("");
        Status st = switch (s.toUpperCase()) {
            case "COMPLIANT" -> Status.COMPLIANT;
            case "NON_COMPLIANT" -> Status.NON_COMPLIANT;
            case "QUESTIONABLE" -> Status.QUESTIONABLE;
            default -> Status.NOT_COVERED;
        };
        return new Report(st, Http.text(report, "reportDate"), "api");
    }
}
