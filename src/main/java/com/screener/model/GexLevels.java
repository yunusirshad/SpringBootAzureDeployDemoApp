package com.screener.model;

import java.time.Instant;
import java.util.List;

/** Call wall (largest call gamma), put wall (largest put gamma) and king (largest net gamma, either sign). */
public record GexLevels(String symbol, Double spot, Double callWall, Double king, Double putWall,
                        Double netGex, List<String> expirations, Instant asOf, String error) {

    public static GexLevels unavailable(String symbol, String error) {
        return new GexLevels(symbol, null, null, null, null, null, List.of(), Instant.now(), error);
    }
}
