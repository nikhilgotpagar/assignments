package com.paytmassignment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String adminToken,
        long holdTtlSeconds,
        int perUserLimitDefault,
        long expirySweepMs) {
}
