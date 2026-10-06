package com.marlabs.gateway.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the downstream Python service.
 *
 * @param baseUrl root URL, e.g. {@code http://localhost:8000}
 * @param timeout budget applied to both connect and read
 */
@ConfigurationProperties(prefix = "policy-service")
public record PolicyServiceProperties(String baseUrl, Duration timeout) {

    public PolicyServiceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
    }
}
