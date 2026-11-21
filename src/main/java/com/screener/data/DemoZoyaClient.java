package com.screener.data;

import java.time.Clock;
import java.util.Map;

import com.screener.model.ZoyaCheck;

/** Fixed answers for the demo data. Not real compliance data. */
public class DemoZoyaClient implements ZoyaClient {
    private static final Map<String, String> STATUS = Map.of(
            "DEMA", "COMPLIANT", "DEMB", "COMPLIANT", "DEMC", "NON_COMPLIANT", "DEMD", "COMPLIANT",
            "DEME", "QUESTIONABLE", "DEMF", "COMPLIANT", "DEMG", "COMPLIANT", "DEMH", "COMPLIANT");

    private final Clock clock;

    public DemoZoyaClient(Clock clock) {
        this.clock = clock;
    }

    @Override
    public ZoyaCheck check(String symbol) {
        String s = STATUS.get(symbol);
        if (s == null) throw new IllegalStateException("No demo report for " + symbol);
        return new ZoyaCheck(symbol, s, clock.instant(), "LIVE", null);
    }
}
