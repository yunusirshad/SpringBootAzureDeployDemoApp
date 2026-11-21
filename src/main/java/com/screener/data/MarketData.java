package com.screener.data;

import java.util.List;
import java.util.Map;

import com.screener.model.Bar;
import com.screener.model.Fundamentals;
import com.screener.model.ListedSymbol;
import com.screener.model.OptionChain;
import com.screener.model.Quote;

/** Where the screen gets its numbers. One live implementation (Yahoo) and one offline demo. */
public interface MarketData {

    String name();

    /** Nasdaq and NYSE common stocks. */
    List<ListedSymbol> universe();

    /** Price and market cap for many symbols at once. Missing symbols are left out. */
    Map<String, Quote> quotes(List<String> symbols);

    Fundamentals fundamentals(String symbol);

    /** Daily bars, oldest first. */
    List<Bar> dailyBars(String symbol, int days);

    /** Intraday bars, oldest first, with premarket and after-hours when prePost is true. */
    List<Bar> intradayBars(String symbol, String interval, int days, boolean prePost);

    /** Calls and puts for the nearest maxExpirations expiries. */
    OptionChain optionChain(String symbol, int maxExpirations);
}
