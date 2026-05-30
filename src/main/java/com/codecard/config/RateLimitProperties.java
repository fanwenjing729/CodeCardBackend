package com.codecard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(boolean enabled, List<PathLimit> paths) {

    public record PathLimit(String path, int limit, int durationSeconds) {
        public Duration duration() {
            return Duration.ofSeconds(durationSeconds);
        }
    }
}
