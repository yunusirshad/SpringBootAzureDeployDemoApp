package com.personalscreener.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One full screen run. */
public class Snapshot {
    public String generatedAt;
    public String screenSource;
    public String screenNote;
    public int screened;
    public int passedFilters;
    public int compliant;
    public boolean zoyaConfigured;
    public boolean chainsAvailable;
    public String lastChainRun;
    public MarketPulse pulse = new MarketPulse();
    public List<StockRow> rows = new ArrayList<>();
    public Map<String, Object> filters = new TreeMap<>();
}
