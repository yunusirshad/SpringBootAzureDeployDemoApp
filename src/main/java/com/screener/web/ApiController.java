package com.screener.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.screener.config.ScreenerProperties;
import com.screener.data.MarketData;
import com.screener.model.GexLevels;
import com.screener.model.ScreenResult;
import com.screener.model.ScreenRow;
import com.screener.model.Snapshot;
import com.screener.model.TradePlan;
import com.screener.model.ZoyaCheck;
import com.screener.service.CoreStocksService;
import com.screener.service.ScreenerService;
import com.screener.service.SnapshotService;
import com.screener.service.PlanService;
import com.screener.service.ZoyaService;

import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ScreenerProperties props;
    private final MarketData market;
    private final ScreenerService screener;
    private final CoreStocksService core;
    private final SnapshotService snapshots;
    private final PlanService plans;
    private final ZoyaService zoya;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private volatile String lastRunError;

    public ApiController(ScreenerProperties props, MarketData market, ScreenerService screener, CoreStocksService core,
                         SnapshotService snapshots, PlanService plans, ZoyaService zoya) {
        this.props = props;
        this.market = market;
        this.screener = screener;
        this.core = core;
        this.snapshots = snapshots;
        this.plans = plans;
        this.zoya = zoya;
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", props.getRules());
        m.put("account", props.getAccount());
        m.put("provider", market.name());
        m.put("zoyaCacheHours", props.getZoya().getCacheHours());
        m.put("zoyaKeySet", props.getZoya().getApiKey() != null && !props.getZoya().getApiKey().isBlank());
        return m;
    }

    @GetMapping("/screen")
    public Map<String, Object> screen(@RequestParam(defaultValue = "false") boolean all) {
        ScreenResult r = screener.latest();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", screener.isRunning());
        m.put("lastRunError", lastRunError);
        m.put("result", all ? r : new ScreenResult(r.runAt(), r.nyDate(), r.provider(), r.universeCount(), r.bandCount(),
                r.evaluatedCount(), r.ratioPassCount(), r.zoyaCompliantCount(), r.rows(), List.of(), r.warnings()));
        return m;
    }

    /** Starts a screen. wait=true runs it in this request and returns the result. */
    @PostMapping("/screen/run")
    public ResponseEntity<Map<String, Object>> run(@RequestParam(defaultValue = "false") boolean refreshZoya,
                                                   @RequestParam(defaultValue = "false") boolean wait) {
        if (screener.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("running", true, "message", "A screen is already running."));
        }
        if (wait) {
            screener.run(refreshZoya);
            snapshots.build();
            return ResponseEntity.ok(screen(false));
        }
        background.submit(() -> {
            try {
                lastRunError = null;
                screener.run(refreshZoya);
                snapshots.build();
            } catch (Exception e) {
                lastRunError = e.getMessage();
            }
        });
        return ResponseEntity.accepted().body(Map.of("running", true, "message", "Screen started."));
    }

    /** One name through the full screener row (with GEX). */
    @GetMapping("/row/{symbol}")
    public ScreenRow row(@PathVariable String symbol, @RequestParam(defaultValue = "false") boolean refreshZoya) {
        return screener.evaluate(sym(symbol), refreshZoya, true);
    }

    @GetMapping("/analyze/{symbol}")
    public Map<String, Object> analyze(@PathVariable String symbol,
                                       @RequestParam(required = false) Double account,
                                       @RequestParam(required = false) Double riskPercent,
                                       @RequestParam(required = false) String side) {
        String s = sym(symbol);
        TradePlan plan = plans.analyze(s, account, riskPercent, side);
        ScreenRow row = screener.evaluate(s, false, true);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", plan);
        m.put("gex", row.gex());
        m.put("row", row);
        return m;
    }

    @GetMapping("/gex/{symbol}")
    public GexLevels gex(@PathVariable String symbol) {
        return screener.evaluate(sym(symbol), false, true).gex();
    }

    @GetMapping("/zoya/{symbol}")
    public ZoyaCheck zoya(@PathVariable String symbol, @RequestParam(defaultValue = "false") boolean refresh) {
        return zoya.check(sym(symbol), refresh);
    }

    @GetMapping("/core")
    public Map<String, Object> core() {
        return Map.of("nyDate", core.day(), "rows", core.list());
    }

    /** Adds a name to today's core if its full row passes the screen with a fresh compliant Zoya read. */
    @PostMapping("/core/{symbol}")
    public ResponseEntity<Map<String, Object>> addCore(@PathVariable String symbol) {
        ScreenRow row = screener.evaluate(sym(symbol), false, true);
        if (!row.passed()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("added", false, "row", row,
                    "message", "Not added: " + String.join("; ", row.failedReasons())));
        }
        boolean added = !core.merge(List.of(row)).isEmpty();
        return ResponseEntity.ok(Map.of("added", added, "row", row));
    }

    @DeleteMapping("/core/{symbol}")
    public Map<String, Object> removeCore(@PathVariable String symbol) {
        return Map.of("removed", core.remove(sym(symbol)));
    }

    @GetMapping("/snapshot")
    public Snapshot snapshot(@RequestParam(required = false) LocalDate date) {
        return date == null ? snapshots.today() : snapshots.forDate(date);
    }

    @PostMapping("/snapshot")
    public Snapshot rebuildSnapshot() {
        return snapshots.build();
    }

    static String sym(String s) {
        String u = s.trim().toUpperCase(Locale.ROOT);
        if (!u.matches("[A-Z0-9.\\-]{1,10}")) throw new IllegalArgumentException("Bad symbol: " + s);
        return u.replace('.', '-');
    }
}
