package com.personalscreener.model;

import java.time.LocalDate;

/** Option open-interest levels for the next listed expiry. Percents are shares of that expiry's open interest. */
public record GexLevels(LocalDate expiry,
                        double callWall, double callWallPct,
                        double king, double kingPct,
                        double putWall, double putWallPct,
                        double totalOi, double callOi, double putOi, double callPutRatio, double netGex) {}
