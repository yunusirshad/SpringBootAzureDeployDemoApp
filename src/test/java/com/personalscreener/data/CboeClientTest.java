package com.personalscreener.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A file in the shape of cdn.cboe.com/api/global/delayed_quotes/options/{SYMBOL}.json. */
class CboeClientTest {

    private static final String JSON = """
            {"timestamp": "2026-11-23 13:45:02", "data": {"symbol": "MITK", "current_price": 16.38, "close": 16.38,
              "options": [
                {"option": "MITK261120C00015000", "open_interest": 999, "iv": 0.5},
                {"option": "MITK261127C00017500", "open_interest": 1200, "iv": 0.52, "gamma": 0.21},
                {"option": "MITK261127C00020000", "open_interest": 300, "iv": 0.6},
                {"option": "MITK261127P00015000", "open_interest": 800, "iv": 0.55},
                {"option": "MITK261218C00017500", "open_interest": 2500, "iv": 0.48},
                {"option": "MITK261218P00012500", "open_interest": 1900, "iv": 0.5},
                {"option": "MITK270115C00025000", "open_interest": 50, "iv": 0.45},
                {"option": "MITK270618C00030000", "open_interest": 10, "iv": 0.44},
                {"option": "not-an-occ-code", "open_interest": 5}
              ]}}
            """;

    @Test
    void parsesOccContractsIntoExpiriesInsideTheWindow() throws Exception {
        var root = new ObjectMapper().readTree(JSON);
        List<OptionChain> chains = CboeClient.parse("MITK", root, LocalDate.of(2026, 11, 23), 45, 8);
        // 2026-11-20 has expired; 2027-01-15 and 2027-06-18 are past 45 days
        assertEquals(List.of(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 18)),
                chains.stream().map(OptionChain::expiry).toList());
        OptionChain nov = chains.get(0);
        assertEquals(16.38, nov.spot(), 1e-9);
        assertEquals(2, nov.calls().size());
        assertEquals(17.5, nov.calls().get(0).strike(), 1e-9);
        assertEquals(1200, nov.calls().get(0).openInterest(), 1e-9);
        assertEquals(15.0, nov.puts().get(0).strike(), 1e-9);
        assertEquals(12.5, chains.get(1).puts().get(0).strike(), 1e-9);
    }

    @Test
    void alwaysKeepsTheNearestExpiry() throws Exception {
        var root = new ObjectMapper().readTree(JSON);
        List<OptionChain> chains = CboeClient.parse("MITK", root, LocalDate.of(2026, 11, 23), 0, 8);
        assertEquals(1, chains.size());
        assertEquals(LocalDate.of(2026, 11, 27), chains.get(0).expiry());
    }
}
