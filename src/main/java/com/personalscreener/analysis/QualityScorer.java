package com.personalscreener.analysis;

import com.personalscreener.config.ScreenerProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tradability score out of 100. Five clips, 20 points each: full marks inside the clip,
 * fading by distance (in log terms) outside it, zero a factor of 10 away.
 * A missing quote scores the spread clip at half marks; a missing float scores zero.
 */
@Component
public class QualityScorer {

    public record Result(int score, boolean pass, List<String> reasons) {}

    private final ScreenerProperties.Quality q;

    public QualityScorer(ScreenerProperties props) { this.q = props.getQuality(); }

    public Result score(double relVol, double dollarVolume, double spreadBps, double floatShares, double atrPct) {
        double s = clip(relVol, q.getRelVolMin(), q.getRelVolMax())
                + clip(dollarVolume, q.getDollarVolMin(), q.getDollarVolMax())
                + (Double.isNaN(spreadBps) ? 10 : clip(spreadBps, q.getSpreadMin(), q.getSpreadMax()))
                + clip(floatShares, q.getFloatMin(), q.getFloatMax())
                + clip(atrPct, q.getAtrPctMin(), q.getAtrPctMax());
        int score = (int) Math.round(s);
        List<String> why = new ArrayList<>();
        if (score < q.getPassScore()) why.add("Score under " + q.getPassScore());
        if (Double.isNaN(dollarVolume) || dollarVolume < q.getMinDollarVolume()) why.add("Dollar volume under $" + fmtM(q.getMinDollarVolume()));
        if (!Double.isNaN(spreadBps) && spreadBps > q.getMaxSpreadBps()) why.add("Spread over " + (int) q.getMaxSpreadBps() + " bps");
        if (Double.isNaN(floatShares) || floatShares <= 0) why.add("Float unknown");
        return new Result(score, why.isEmpty(), why);
    }

    static double clip(double v, double lo, double hi) {
        if (Double.isNaN(v) || v <= 0) return 0;
        if (v >= lo && v <= hi) return 20;
        double off = v < lo ? Math.log10(lo / v) : Math.log10(v / hi);
        return Math.max(0, 20 * (1 - off));
    }

    private static String fmtM(double v) { return v >= 1e6 ? (int) (v / 1e6) + "M" : String.valueOf((int) v); }
}
