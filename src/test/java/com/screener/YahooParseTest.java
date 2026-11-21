package com.screener.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.screener.model.ListedSymbol;

class YahooParseTest {
    @Test
    void nasdaqListKeepsCommonStockOnly() {
        String txt = """
                Symbol|Security Name|Market Category|Test Issue|Financial Status|Round Lot Size|ETF|NextShares
                AAPL|Apple Inc. - Common Stock|Q|N|N|100|N|N
                QQQ|Invesco QQQ Trust|G|N|N|100|Y|N
                ZZZT|Test Co|Q|Y|N|100|N|N
                ABCDW|ABC Corp - Warrants|S|N|N|100|N|N
                XYZ|XYZ 8.25% Notes due 2030|G|N|N|100|N|N
                File Creation Time: 1116202608:00|||||||
                """;
        List<ListedSymbol> l = YahooMarketData.parseNasdaqListed(txt);
        assertThat(l).extracting(ListedSymbol::symbol).containsExactly("AAPL");
        assertThat(l.get(0).exchange()).isEqualTo("NASDAQ");
    }

    @Test
    void otherListKeepsNyseOnly() {
        String txt = """
                ACT Symbol|Security Name|Exchange|CQS Symbol|ETF|Round Lot Size|Test Issue|NASDAQ Symbol
                F|Ford Motor Company Common Stock|N|F|N|100|N|F
                BRK.B|Berkshire Hathaway Inc. Class B|N|BRK.B|N|100|N|BRK=B
                SPY|SPDR S&P 500|P|SPY|Y|100|N|SPY
                AMEX1|Some Amex Co|A|AMEX1|N|100|N|AMEX1
                File Creation Time: 1116202608:00|||||||
                """;
        assertThat(YahooMarketData.parseOtherListed(txt)).extracting(ListedSymbol::symbol).containsExactly("F", "BRK-B");
    }
}
