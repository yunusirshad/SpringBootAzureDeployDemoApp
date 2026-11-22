package com.personalscreener.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.personalscreener.data.SecClient;
import com.personalscreener.model.Num;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Quarter-by-quarter results from SEC EDGAR XBRL facts, with margins and year-over-year change. */
@Service
public class AnalyzeService {

    private static final List<String> REVENUE = List.of("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues",
            "SalesRevenueNet", "RevenueFromContractWithCustomerIncludingAssessedTax");
    private static final List<String> GROSS = List.of("GrossProfit");
    private static final List<String> OPERATING = List.of("OperatingIncomeLoss");
    private static final List<String> NET = List.of("NetIncomeLoss", "ProfitLoss");
    private static final List<String> EPS = List.of("EarningsPerShareDiluted", "EarningsPerShareBasic");

    private final SecClient sec;

    public AnalyzeService(SecClient sec) { this.sec = sec; }

    public record Quarter(String period, LocalDate end, String form, Double revenue, Double grossProfit, Double operatingIncome,
                          Double netIncome, Double eps, Double grossMargin, Double operatingMargin, Double netMargin,
                          Double revenueYoY, Double epsYoY) {}

    public Map<String, Object> analyze(String ticker) throws Exception {
        String cik = sec.cik(ticker);
        if (cik == null) throw new NoSuchElementException("No SEC filer for " + ticker + " (foreign filers using 20-F/40-F have no quarterly XBRL)");
        JsonNode facts = sec.companyFacts(cik);
        JsonNode gaap = facts.path("facts").path("us-gaap");

        Map<LocalDate, Double> rev = quarterly(gaap, REVENUE, "USD");
        Map<LocalDate, Double> gross = quarterly(gaap, GROSS, "USD");
        Map<LocalDate, Double> op = quarterly(gaap, OPERATING, "USD");
        Map<LocalDate, Double> net = quarterly(gaap, NET, "USD");
        Map<LocalDate, Double> eps = quarterly(gaap, EPS, "USD/shares");
        Map<LocalDate, String> forms = new HashMap<>();
        formsFor(gaap, REVENUE, forms);

        TreeSet<LocalDate> ends = new TreeSet<>(rev.keySet());
        ends.addAll(net.keySet());
        List<Quarter> out = new ArrayList<>();
        for (LocalDate end : ends.descendingSet()) {
            if (out.size() >= 8) break;
            Double r = rev.get(end), g = gross.get(end), o = op.get(end), n = net.get(end), e = eps.get(end);
            LocalDate ya = find(ends, end.minusYears(1));
            Double rYa = ya == null ? null : rev.get(ya), eYa = ya == null ? null : eps.get(ya);
            out.add(new Quarter(label(end), end, forms.getOrDefault(end, ""), r, g, o, n, e,
                    pct(g, r), pct(o, r), pct(n, r),
                    r != null && rYa != null && rYa != 0 ? Num.round((r / rYa - 1) * 100, 1) : null,
                    e != null && eYa != null && eYa != 0 ? Num.round((e - eYa) / Math.abs(eYa) * 100, 1) : null));
        }
        double ttmRev = 0, ttmNet = 0;
        int k = 0;
        for (Quarter q : out) {
            if (k++ >= 4) break;
            ttmRev += q.revenue() == null ? 0 : q.revenue();
            ttmNet += q.netIncome() == null ? 0 : q.netIncome();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", ticker.toUpperCase());
        m.put("company", facts.path("entityName").asText());
        m.put("cik", cik);
        m.put("quarters", out);
        m.put("ttmRevenue", ttmRev);
        m.put("ttmNetIncome", ttmNet);
        m.put("ttmNetMargin", ttmRev != 0 ? Num.round(ttmNet / ttmRev * 100, 1) : null);
        m.put("summary", summary(out));
        return m;
    }

    /** Three-month values by period end. Fourth quarters are derived as the fiscal year minus the first nine months. */
    static Map<LocalDate, Double> quarterly(JsonNode gaap, List<String> concepts, String unit) {
        for (String concept : concepts) {
            JsonNode arr = gaap.path(concept).path("units").path(unit);
            if (!arr.isArray() || arr.isEmpty()) continue;
            Map<LocalDate, Double> q = new TreeMap<>();
            Map<LocalDate, double[]> annual = new TreeMap<>(); // end -> {value, startEpochDay}
            for (JsonNode f : arr) {
                if (!f.has("start") || !f.has("end")) continue;
                LocalDate s = LocalDate.parse(f.get("start").asText()), e = LocalDate.parse(f.get("end").asText());
                long days = ChronoUnit.DAYS.between(s, e);
                double v = f.path("val").asDouble();
                if (days >= 80 && days <= 100) q.put(e, v);
                else if (days >= 350 && days <= 380) annual.put(e, new double[]{v, s.toEpochDay()});
            }
            boolean perShare = unit.contains("shares");
            for (var en : annual.entrySet()) {
                LocalDate end = en.getKey();
                if (q.containsKey(end)) continue;
                LocalDate start = LocalDate.ofEpochDay((long) en.getValue()[1]);
                List<Double> inside = new ArrayList<>();
                for (var qe : q.entrySet()) if (qe.getKey().isAfter(start) && qe.getKey().isBefore(end)) inside.add(qe.getValue());
                if (inside.size() == 3 && !perShare) q.put(end, en.getValue()[0] - inside.stream().mapToDouble(Double::doubleValue).sum());
            }
            if (!q.isEmpty()) return q;
        }
        return Map.of();
    }

    private static void formsFor(JsonNode gaap, List<String> concepts, Map<LocalDate, String> forms) {
        for (String c : concepts) {
            for (JsonNode f : gaap.path(c).path("units").path("USD")) {
                if (f.has("end")) forms.putIfAbsent(LocalDate.parse(f.get("end").asText()), f.path("form").asText());
            }
        }
    }

    private static LocalDate find(NavigableSet<LocalDate> ends, LocalDate target) {
        LocalDate lo = ends.floor(target.plusDays(10));
        return lo != null && Math.abs(ChronoUnit.DAYS.between(lo, target)) <= 10 ? lo : null;
    }

    private static Double pct(Double a, Double b) { return a == null || b == null || b == 0 ? null : Num.round(a / b * 100, 1); }

    private static String label(LocalDate end) { return "Q ending " + end; }

    private static List<String> summary(List<Quarter> q) {
        List<String> s = new ArrayList<>();
        if (q.isEmpty()) { s.add("No quarterly facts were found."); return s; }
        Quarter last = q.get(0);
        if (last.revenueYoY() != null) s.add("Revenue " + (last.revenueYoY() >= 0 ? "grew " : "fell ") + Math.abs(last.revenueYoY()) + "% from a year earlier.");
        if (q.size() > 4 && last.grossMargin() != null && q.get(4).grossMargin() != null) {
            double d = last.grossMargin() - q.get(4).grossMargin();
            s.add("Gross margin " + last.grossMargin() + "%, " + (d >= 0 ? "up " : "down ") + Num.round(Math.abs(d), 1) + " points year over year.");
        }
        if (last.netIncome() != null) s.add(last.netIncome() >= 0 ? "Profitable in the latest quarter." : "A net loss in the latest quarter.");
        if (last.epsYoY() != null) s.add("Diluted EPS " + (last.epsYoY() >= 0 ? "up " : "down ") + Math.abs(last.epsYoY()) + "% year over year.");
        return s;
    }
}
