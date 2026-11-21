package com.screener.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.screener.config.ScreenerProperties;
import com.screener.data.MarketData;
import com.screener.model.Bar;
import com.screener.model.ScreenResult;
import com.screener.model.ScreenRow;
import com.screener.model.Snapshot;
import com.screener.service.CoreStocksService;
import com.screener.service.Csv;
import com.screener.service.ScreenerService;
import com.screener.service.SnapshotService;

/**
 * Public, read-only exports: the screen, the bot snapshot and 15-minute bars (premarket and after-hours
 * included). JSON by default, CSV with format=csv. If screener.export.token is set, ?token= must match.
 */
@RestController
@RequestMapping("/public/export")
public class ExportController {
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final ScreenerProperties props;
    private final ScreenerService screener;
    private final CoreStocksService core;
    private final SnapshotService snapshots;
    private final MarketData market;

    public ExportController(ScreenerProperties props, ScreenerService screener, CoreStocksService core,
                            SnapshotService snapshots, MarketData market) {
        this.props = props;
        this.screener = screener;
        this.core = core;
        this.snapshots = snapshots;
        this.market = market;
    }

    /** The screen list. include=core adds today's core rows (same row shape). */
    @GetMapping("/screen")
    public ResponseEntity<?> screen(@RequestParam(defaultValue = "json") String format,
                                    @RequestParam(required = false) String include,
                                    @RequestParam(required = false) String token) {
        auth(token);
        ScreenResult r = screener.latest();
        List<ScreenRow> coreRows = "core".equalsIgnoreCase(include) ? core.list() : List.of();
        if ("csv".equalsIgnoreCase(format)) {
            Map<String, ScreenRow> all = new LinkedHashMap<>();
            r.rows().forEach(x -> all.put(x.symbol(), x));
            coreRows.forEach(x -> all.putIfAbsent(x.symbol(), x));
            return ResponseEntity.ok().contentType(CSV).body(Csv.screen(List.copyOf(all.values())));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runAt", r.runAt());
        m.put("nyDate", r.nyDate());
        m.put("provider", r.provider());
        m.put("rows", r.rows());
        if (!coreRows.isEmpty() || "core".equalsIgnoreCase(include)) m.put("core", coreRows);
        return ResponseEntity.ok(m);
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(@RequestParam(defaultValue = "json") String format,
                                      @RequestParam(required = false) LocalDate date,
                                      @RequestParam(required = false) String token) {
        auth(token);
        Snapshot s = date == null ? snapshots.today() : snapshots.forDate(date);
        if ("csv".equalsIgnoreCase(format)) return ResponseEntity.ok().contentType(CSV).body(Csv.snapshot(s.entries()));
        return ResponseEntity.ok(s);
    }

    /** 15-minute bars with premarket and after-hours. days 1–60 (default 5). */
    @GetMapping("/bars/{symbol}")
    public ResponseEntity<?> bars(@PathVariable String symbol,
                                  @RequestParam(defaultValue = "5") int days,
                                  @RequestParam(defaultValue = "json") String format,
                                  @RequestParam(required = false) String token) {
        auth(token);
        String s = ApiController.sym(symbol);
        int d = Math.max(1, Math.min(days, 60));
        List<Bar> bars = market.intradayBars(s, "15m", d, true);
        if ("csv".equalsIgnoreCase(format)) return ResponseEntity.ok().contentType(CSV).body(Csv.bars(s, bars));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", s);
        m.put("interval", "15m");
        m.put("days", d);
        m.put("includesPrePost", true);
        m.put("bars", bars);
        return ResponseEntity.ok(m);
    }

    private void auth(String token) {
        String want = props.getExport().getToken();
        if (want != null && !want.isBlank() && !want.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "export token required");
        }
    }
}
