package com.screener.model;

/** One listed common stock: ticker as Yahoo spells it, the company name, and NASDAQ or NYSE. */
public record ListedSymbol(String symbol, String name, String exchange) {}
