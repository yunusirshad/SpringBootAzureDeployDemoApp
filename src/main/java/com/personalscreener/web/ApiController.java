package com.personalscreener.web;

import com.personalscreener.config.ScreenerProperties;
import com.personalscreener.market.MarketClock;
import com.personalscreener.model.Alert;
import com.personalscreener.model.Snapshot;
import com.personalscreener.model.StockRow;
import com.personalscreener.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ScreenService screen;
    private final WatchlistService watchlist;
    private final AlertService alerts;
    private final AnalyzeService analyze;
    private final TechnicalsService technicals;
    private final MarketClock clock;
    private final ScreenerProperties props;

    public ApiController(ScreenService screen, WatchlistService watchlist, AlertService alerts, AnalyzeService analyze,
                         TechnicalsService technicals, MarketClock clock, ScreenerProperties props) {
        this.screen = screen;
        this.watchlist = watchlist;
        this.alerts = alerts;
        this.analyze = analyze;
        this.technicals = technicals;
        this.clock = clock;
        this.props = props;
    }

    /** The full screen. */
    @GetMapping("/screen")
    public Map<String, Object> screen() {
        Snapshot s = screen.snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshot", s);
        m.put("running", screen.isRunning());
        m.put("now", clock.now().toOffsetDateTime().toString());
        m.put("session", clock.sessionLabel());
        m.put("inRefreshWindow", clock.inRefreshWindow());
        return m;
    }

    /** Light payload for the 10-second price poll. */
    @GetMapping("/prices")
    public Map<String, Object> prices() {
        Snapshot s = screen.snapshot();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockRow r : s.rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("symbol", r.symbol);
            p.put("price", r.price);
            p.put("changePct", r.changePct);
            p.put("dayRange", r.dayRange);
            p.put("atr", r.atr);
            p.put("rangeUsedPct", r.rangeUsedPct);
            p.put("rangeLeft", r.rangeLeft);
            p.put("relVol", r.relVol);
            p.put("relVol30", r.relVol30);
            p.put("state", r.state);
            p.put("toBreakoutPct", r.toBreakoutPct);
            p.put("qualityScore", r.qualityScore);
            p.put("qualityPass", r.qualityPass);
            p.put("spreadBps", r.spreadBps);
            p.put("list", r.list);
            rows.add(p);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", clock.now().toOffsetDateTime().toString());
        m.put("session", clock.sessionLabel());
        m.put("pulse", s.pulse);
        m.put("rows", rows);
        m.put("alerts", screen.recentAlerts());
        m.put("generatedAt", s.generatedAt);
        m.put("running", screen.isRunning());
        return m;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return Map.of("started", screen.refreshAsync());
    }

    // ---- watchlist ------------------------------------------------------------------------

    @GetMapping("/watchlist")
    public List<String> watchlist() { return watchlist.all(); }

    @PostMapping("/watchlist")
    public Map<String, Object> add(@RequestBody Map<String, String> body) {
        List<String> added = watchlist.add(body.getOrDefault("tickers", ""));
        if (!added.isEmpty()) screen.refreshAsync();
        return Map.of("added", added, "watchlist", watchlist.all());
    }

    @DeleteMapping("/watchlist/{ticker}")
    public Map<String, Object> remove(@PathVariable String ticker) {
        boolean removed = watchlist.remove(ticker);
        if (removed) screen.snapshot().rows.removeIf(r -> r.symbol.equalsIgnoreCase(ticker) && !r.screenPass);
        return Map.of("removed", removed, "watchlist", watchlist.all());
    }

    @GetMapping(value = "/watchlist/export", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"watchlist.txt\"")
                .body(String.join("\n", watchlist.all()) + "\n");
    }

    // ---- alerts ---------------------------------------------------------------------------

    @GetMapping("/alerts")
    public List<Alert> alerts() { return alerts.all(); }

    @PostMapping("/alerts")
    public Alert createAlert(@RequestBody Map<String, Object> body) {
        double level = Double.parseDouble(String.valueOf(body.getOrDefault("level", "0")));
        return alerts.create(String.valueOf(body.get("symbol")), String.valueOf(body.get("type")), level,
                body.get("note") == null ? "" : String.valueOf(body.get("note")));
    }

    @DeleteMapping("/alerts/{id}")
    public Map<String, Object> deleteAlert(@PathVariable String id) { return Map.of("deleted", alerts.delete(id)); }

    // ---- analyze / technicals ----------------------------------------------------------------

    @GetMapping("/analyze/{ticker}")
    public Map<String, Object> analyze(@PathVariable String ticker) throws Exception {
        return analyze.analyze(clean(ticker));
    }

    @GetMapping("/technicals/{ticker}")
    public Map<String, Object> technicals(@PathVariable String ticker) throws Exception {
        return technicals.technicals(clean(ticker));
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("filters", props.getFilters());
        m.put("quality", props.getQuality());
        m.put("zoyaConfigured", !props.getZoya().getApiKey().isBlank());
        m.put("maxAnalyzed", props.getMaxAnalyzed());
        return m;
    }

    private static String clean(String t) {
        List<String> p = WatchlistService.parse(t);
        if (p.size() != 1) throw new IllegalArgumentException("Not a ticker: " + t);
        return p.get(0);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failed(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
