package com.screener.model;

public record Quote(String symbol, String name, String exchange, Double price, Double marketCap) {}
