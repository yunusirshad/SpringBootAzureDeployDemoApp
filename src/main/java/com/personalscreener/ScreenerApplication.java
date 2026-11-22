package com.personalscreener;

import com.personalscreener.config.ScreenerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ScreenerProperties.class)
public class ScreenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScreenerApplication.class, args);
    }
}
