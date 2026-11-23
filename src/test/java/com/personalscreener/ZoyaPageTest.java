package com.personalscreener;

import com.personalscreener.data.ZoyaClient;
import com.personalscreener.data.ZoyaClient.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Snippets in the shape of real zoya.finance/stocks/{ticker} pages. */
class ZoyaPageTest {

    private static String page(String ticker, String status, String summary) {
        return "<html><head><meta content=\"" + summary + "\" name=\"description\"/>"
                + "<script type=\"application/ld+json\">{\"name\": \"What guidelines are used to determine if X stock is Shariah-compliant?\"}</script></head>"
                + "<body><div class=\"_1-5em-bottom-margin w-embed\"> " + ticker + " stock is&nbsp; " + status
                + " <div id=\"fs-modal-1-popup\" role=\"dialog\"></div></div>"
                + "<p>As of <span>November 23, 2026</span>, " + summary + "</p>"
                + "<h3>What guidelines are used to determine if Mitek Systems Inc stock is Shariah-compliant?</h3></body></html>";
    }

    @Test
    void compliant() {
        var r = ZoyaClient.parsePage("MITK", page("MITK", "Shariah-compliant", "MITK is Shariah-compliant and therefore halal"));
        assertEquals(Status.COMPLIANT, r.status());
        assertEquals("2026-11-23", r.reportDate());
    }

    @Test
    void notCompliantIsNotReadAsCompliant() {
        var r = ZoyaClient.parsePage("JPM", page("JPM", "not Shariah-compliant", "No. JPM is not Shariah-compliant under AAOIFI standards"));
        assertEquals(Status.NON_COMPLIANT, r.status());
    }

    @Test
    void questionable() {
        assertEquals(Status.QUESTIONABLE, ZoyaClient.parsePage("XYZ", page("XYZ", "Questionable", "XYZ is questionable")).status());
    }

    @Test
    void tickerIsMatchedInAnyCase() {
        assertEquals(Status.COMPLIANT, ZoyaClient.parsePage("mitk", page("MITK", "Shariah-compliant", "")).status());
    }

    @Test
    void fallsBackToTheSummaryLine() {
        String html = "<p>No. As of November 23, 2026, JPMorgan Chase (JPM) is not Shariah-compliant under AAOIFI standards.</p>";
        assertEquals(Status.NON_COMPLIANT, ZoyaClient.parsePage("JPM", html).status());
    }

    @Test
    void pageWithoutStatusIsNull() {
        assertNull(ZoyaClient.parsePage("MITK", "<html><title>Not Found</title></html>"));
    }
}
