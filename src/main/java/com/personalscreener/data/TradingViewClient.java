package com.personalscreener.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalscreener.config.ScreenerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** TradingView's public screener endpoint (scanner.tradingview.com). */
@Component
public class TradingViewClient {

    static final List<String> COLUMNS = List.of(
            "name", "description", "exchange", "close", "change", "volume",
            "average_volume_10d_calc", "average_volume_30d_calc", "relative_volume_10d_calc",
            "market_cap_basic", "price_target_average", "debt_to_equity_fq", "total_debt_fq",
            "total_assets_fq", "total_equity_fq", "cash_n_short_term_invest_fq",
            "total_revenue_yoy_growth_ttm", "float_shares_outstanding", "earnings_release_next_date");

    private final Http http;
    private final ScreenerProperties props;

    public TradingViewClient(ObjectMapper mapper, ScreenerProperties props) {
        this.http = new Http(mapper);
        this.props = props;
    }

    private Map<String, String> headers() {
        return Map.of("Content-Type", "application/json", "Accept", "application/json",
                "Origin", "https://www.tradingview.com", "Referer", "https://www.tradingview.com/",
                "User-Agent", props.getYahoo().getUserAgent());
    }

    private static Map<String, Object> f(String left, String op, Object right) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("left", left);
        m.put("operation", op);
        m.put("right", right);
        return m;
    }

    /** The coarse screen TradingView can do server side. The ratio filters are applied afterwards. */
    public List<Candidate> screen() throws IOException, InterruptedException {
        ScreenerProperties.Filters fl = props.getFilters();
        List<Object> filter = new ArrayList<>();
        filter.add(f("exchange", "in_range", fl.getExchanges()));
        filter.add(f("type", "equal", "stock"));
        filter.add(f("is_primary", "equal", true));
        filter.add(f("close", "in_range", List.of(fl.getPriceMin(), fl.getPriceMax())));
        filter.add(f("market_cap_basic", "greater", fl.getMarketCapMin()));
        filter.add(f("debt_to_equity_fq", "less", fl.getDebtEquityMax()));
        filter.add(f("total_revenue_yoy_growth_ttm", "greater", fl.getRevenueGrowthMin()));
        Map<String, Object> body = baseBody(filter);
        body.put("sort", Map.of("sortBy", "market_cap_basic", "sortOrder", "desc"));
        body.put("range", List.of(0, props.getTradingview().getMaxRows()));
        return parse(http.postJson(props.getTradingview().getScanUrl(), body, headers()));
    }

    /** Same columns for named tickers (the watchlist), whatever their exchange. */
    public List<Candidate> lookup(Collection<String> tickers) throws IOException, InterruptedException {
        if (tickers.isEmpty()) return List.of();
        List<Object> filter = new ArrayList<>();
        filter.add(f("name", "in_range", new ArrayList<>(tickers)));
        filter.add(f("is_primary", "equal", true));
        Map<String, Object> body = baseBody(filter);
        body.put("range", List.of(0, tickers.size() * 3));
        return parse(http.postJson(props.getTradingview().getScanUrl(), body, headers()));
    }

    /** Advancers and decliners on one exchange. */
    public int[] breadth(String exchange) throws IOException, InterruptedException {
        return new int[]{count(exchange, "greater"), count(exchange, "less")};
    }

    private int count(String exchange, String op) throws IOException, InterruptedException {
        List<Object> filter = List.of(f("exchange", "equal", exchange), f("type", "equal", "stock"),
                f("is_primary", "equal", true), f("change", op, 0));
        Map<String, Object> body = baseBody(new ArrayList<>(filter));
        body.put("columns", List.of("name"));
        body.put("range", List.of(0, 1));
        return http.postJson(props.getTradingview().getScanUrl(), body, headers()).path("totalCount").asInt();
    }

    private Map<String, Object> baseBody(List<Object> filter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("markets", List.of("america"));
        body.put("symbols", Map.of("query", Map.of("types", List.of()), "tickers", List.of()));
        body.put("options", Map.of("lang", "en"));
        body.put("filter", filter);
        body.put("columns", COLUMNS);
        return body;
    }

    static List<Candidate> parse(JsonNode root) {
        List<Candidate> out = new ArrayList<>();
        for (JsonNode row : root.path("data")) {
            JsonNode d = row.path("d");
            double earn = Http.num(d.get(18));
            LocalDate earnDate = Double.isNaN(earn) || earn <= 0 ? null
                    : Instant.ofEpochSecond((long) earn).atZone(ZoneId.of("America/New_York")).toLocalDate();
            out.add(new Candidate(
                    d.path(0).asText(), d.path(2).asText(), d.path(1).asText(),
                    Http.num(d.get(3)), Http.num(d.get(4)), Http.num(d.get(5)),
                    Http.num(d.get(6)), Http.num(d.get(7)), Http.num(d.get(8)),
                    Http.num(d.get(9)), Http.num(d.get(10)), Http.num(d.get(11)), Http.num(d.get(12)),
                    Http.num(d.get(13)), Http.num(d.get(14)), Http.num(d.get(15)),
                    Http.num(d.get(16)), Http.num(d.get(17)), earnDate, "tradingview"));
        }
        return out;
    }
}
