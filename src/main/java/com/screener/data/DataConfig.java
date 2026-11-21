package com.screener.data;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.config.ScreenerProperties;

/** Picks the live or demo data. screener.data.provider = yahoo | demo. */
@Configuration
public class DataConfig {

    @Bean
    public MarketData marketData(ScreenerProperties props, ObjectMapper mapper, Clock clock) {
        if ("yahoo".equalsIgnoreCase(props.getData().getProvider())) {
            return new YahooMarketData(mapper, clock, props.getData().getPauseMillis());
        }
        return new DemoMarketData(clock);
    }

    @Bean
    public ZoyaClient zoyaClient(ScreenerProperties props, ObjectMapper mapper, Clock clock) {
        if ("demo".equalsIgnoreCase(props.getData().getProvider())
                && (props.getZoya().getApiKey() == null || props.getZoya().getApiKey().isBlank())) {
            return new DemoZoyaClient(clock);
        }
        return new ZoyaGraphqlClient(props.getZoya(), mapper, clock);
    }
}
