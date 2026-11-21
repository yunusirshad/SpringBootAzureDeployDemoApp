package com.screener.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.screener.model.Fundamentals;
import com.screener.model.Quality;

/**
 * A 0–100 quality score from profitability, cash flow and liquidity. It is shown on the row and never
 * removes a name. Six checks, each worth up to its points; missing numbers score zero for that check.
 */
@Component
public class QualityScorer {

    public Quality score(Fundamentals f) {
        int s = 0;
        List<String> notes = new ArrayList<>();
        s += pts(f.grossMargin(), 0.40, 0.20, 15, "gross margin", notes);
        s += pts(f.operatingMargin(), 0.15, 0.0, 20, "operating margin", notes);
        s += pts(f.profitMargin(), 0.10, 0.0, 20, "net margin", notes);
        s += pts(f.returnOnEquity(), 0.15, 0.0, 20, "ROE", notes);
        if (f.freeCashFlow() == null) {
            notes.add("no free cash flow data");
        } else if (f.freeCashFlow() > 0) {
            s += 15;
        } else {
            notes.add("negative free cash flow");
        }
        s += pts(f.currentRatio(), 1.5, 1.0, 10, "current ratio", notes);
        return new Quality(s, grade(s), notes);
    }

    /** Full points at or above good, half at or above ok, none below. */
    private static int pts(Double v, double good, double ok, int max, String label, List<String> notes) {
        if (v == null) {
            notes.add("no " + label + " data");
            return 0;
        }
        if (v >= good) return max;
        if (v >= ok) {
            notes.add("modest " + label);
            return max / 2;
        }
        notes.add("weak " + label);
        return 0;
    }

    static String grade(int s) {
        if (s >= 80) return "A";
        if (s >= 65) return "B";
        if (s >= 50) return "C";
        if (s >= 35) return "D";
        return "F";
    }
}
