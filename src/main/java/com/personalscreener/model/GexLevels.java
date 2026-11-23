package com.personalscreener.model;

import java.time.LocalDate;

/**
 * Option open-interest levels across the expiries in the window (the next 45 days by default).
 * Call wall and put wall are the strikes with the most call and put open interest; King is the strike with the
 * largest net gamma. Percents are shares of all open interest in the window. {@code expiry} is the expiry that
 * holds the most open interest; {@code asOf} is the day the chain was read.
 */
public record GexLevels(LocalDate expiry,
                        double callWall, double callWallPct, double callWallOi,
                        double king, double kingPct,
                        double putWall, double putWallPct, double putWallOi,
                        double totalOi, double callOi, double putOi, double callPutRatio, double netGex,
                        LocalDate asOf, int expiries, LocalDate lastExpiry) {}
