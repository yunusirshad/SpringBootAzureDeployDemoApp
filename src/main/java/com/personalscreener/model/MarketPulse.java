package com.personalscreener.model;

import java.util.ArrayList;
import java.util.List;

/** The one-line tape read: index ETFs against VWAP plus exchange breadth. */
public class MarketPulse {
    public String regime = "Unknown";
    public List<IndexRead> indexes = new ArrayList<>();
    public Integer nasdaqUp, nasdaqDown, nyseUp, nyseDown;
    public String session;

    public static class IndexRead {
        public String symbol;
        public Double price, vwap, changePct;
        public boolean aboveVwap;
    }
}
