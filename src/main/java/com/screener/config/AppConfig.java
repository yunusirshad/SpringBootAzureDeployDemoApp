package com.screener.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
