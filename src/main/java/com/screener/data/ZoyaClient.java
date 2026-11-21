package com.screener.data;

import com.screener.model.ZoyaCheck;

public interface ZoyaClient {
    /** A live read. Throws on any failure (no key, network, bad reply, no report for the symbol). */
    ZoyaCheck check(String symbol) throws Exception;
}
