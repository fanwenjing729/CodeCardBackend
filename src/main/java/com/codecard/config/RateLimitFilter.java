package com.codecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Only trust X-Forwarded-For from these IPs (localhost + common Docker bridge). */
    private static final Set<String> TRUSTED_PROXIES = Set.of(
            "127.0.0.1", "0:0:0:0:0:0:0:1", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
    );

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, RateLimitProperties.PathLimit> pathMap;
    private final Map<String, BucketEntry> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limit-cleanup");
        t.setDaemon(true);
        return t;
    });

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.pathMap = properties.paths() != null
                ? properties.paths().stream()
                    .collect(Collectors.toMap(RateLimitProperties.PathLimit::path, p -> p))
                : Map.of();
    }

    @jakarta.annotation.PostConstruct
    void startCleanup() {
        cleanup.scheduleWithFixedDelay(() -> {
            Instant cutoff = Instant.now().minusSeconds(600);
            buckets.entrySet().removeIf(e -> e.getValue().lastAccess.isBefore(cutoff));
        }, 10, 10, TimeUnit.MINUTES);
    }

    @jakarta.annotation.PreDestroy
    void stopCleanup() {
        cleanup.shutdown();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        RateLimitProperties.PathLimit limit = pathMap.get(path);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = getClientIP(request);
        String key = ip + "|" + path;
        BucketEntry entry = buckets.computeIfAbsent(key, k -> new BucketEntry(createBucket(limit)));
        entry.lastAccess = Instant.now();

        if (entry.bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded: {} {}", ip, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", String.valueOf(limit.durationSeconds()));
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "error", "too many requests",
                    "retryAfter", limit.durationSeconds()
            ));
        }
    }

    private Bucket createBucket(RateLimitProperties.PathLimit limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit.limit())
                .refillGreedy(limit.limit(), limit.duration())
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private String getClientIP(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String addr) {
        if (TRUSTED_PROXIES.contains(addr)) {
            return true;
        }
        for (String cidr : TRUSTED_PROXIES) {
            int slash = cidr.indexOf('/');
            if (slash < 0) continue;
            try {
                String prefix = cidr.substring(0, slash);
                int bits = Integer.parseInt(cidr.substring(slash + 1));
                if (addrMatchesCIDR(addr, prefix, bits)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private boolean addrMatchesCIDR(String addr, String prefix, int bits) {
        if (bits <= 0) return true;
        byte[] addrBytes = ipToBytes(addr);
        byte[] prefixBytes = ipToBytes(prefix);
        if (addrBytes == null || prefixBytes == null) return false;
        if (addrBytes.length != prefixBytes.length) return false;

        int fullBytes = bits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addrBytes[i] != prefixBytes[i]) return false;
        }
        int remainingBits = bits % 8;
        if (remainingBits > 0) {
            int mask = 0xFF << (8 - remainingBits);
            if ((addrBytes[fullBytes] & mask) != (prefixBytes[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }

    private byte[] ipToBytes(String ip) {
        if (ip.contains(":")) {
            try {
                java.net.Inet6Address addr = (java.net.Inet6Address) java.net.InetAddress.getByName(ip);
                return addr.getAddress();
            } catch (Exception e) {
                return null;
            }
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int val = Integer.parseInt(parts[i]);
                if (val < 0 || val > 255) return null;
                bytes[i] = (byte) val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    private static class BucketEntry {
        final Bucket bucket;
        volatile Instant lastAccess;

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccess = Instant.now();
        }
    }
}
