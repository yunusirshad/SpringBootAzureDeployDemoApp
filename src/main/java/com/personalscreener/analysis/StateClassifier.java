package com.personalscreener.analysis;

/**
 * Names today's tape in four words. Direction comes from the move off yesterday's close.
 * <ul>
 *   <li><b>initiation</b>: the move is at least 0.6 ATR on at least 1.3x relative volume, a real push.</li>
 *   <li><b>distribution / accumulation</b>: the move is at least 0.45 ATR, or volume is at least 1x and
 *       the close sits in the third of the day's range that agrees with the move.</li>
 *   <li><b>manipulation</b>: anything smaller: a drift or shakeout without commitment.</li>
 * </ul>
 */
public final class StateClassifier {

    private StateClassifier() {}

    public static String classify(double changePct, double atrPct, double relVol, double closeLocation) {
        if (Double.isNaN(changePct)) return "Unknown";
        boolean bull = changePct >= 0;
        String side = bull ? "Bull" : "Bear";
        double moveAtr = Double.isNaN(atrPct) || atrPct <= 0 ? 0 : Math.abs(changePct) / atrPct;
        double rv = Double.isNaN(relVol) ? 0 : relVol;
        if (moveAtr >= 0.6 && rv >= 1.3) return side + " initiation";
        boolean closesWithMove = !Double.isNaN(closeLocation) && (bull ? closeLocation >= 2.0 / 3 : closeLocation <= 1.0 / 3);
        if (moveAtr >= 0.45 || (rv >= 1.0 && closesWithMove) || (bull && rv >= 1.0)) {
            return side + (bull ? " accumulation" : " distribution");
        }
        return side + " manipulation";
    }
}
