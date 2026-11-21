package com.screener.model;

import java.util.List;

public record OptionChain(String symbol, double spot, List<OptionContract> contracts) {}
