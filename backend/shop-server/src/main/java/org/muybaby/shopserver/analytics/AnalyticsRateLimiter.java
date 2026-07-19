package org.muybaby.shopserver.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AnalyticsRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRateLimiter.class);
    private static final String KEY_PREFIX = "shop:analytics:rate:";
    private static final DefaultRedisScript<Long> LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local increment = tonumber(ARGV[1])
            local ttlMillis = tonumber(ARGV[2])
            local ipLimit = tonumber(ARGV[3])
            local visitorLimit = tonumber(ARGV[4])

            local ipCount = redis.call('INCRBY', KEYS[1], increment)
            if ipCount == increment then
                redis.call('PEXPIRE', KEYS[1], ttlMillis)
            end
            local visitorCount = redis.call('INCRBY', KEYS[2], increment)
            if visitorCount == increment then
                redis.call('PEXPIRE', KEYS[2], ttlMillis)
            end
            if ipCount > ipLimit or visitorCount > visitorLimit then
                return 0
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AnalyticsRateLimitProperties properties;
    private final AnalyticsClientIpResolver clientIpResolver;
    private final AtomicLong lastFailureLogAt = new AtomicLong(0L);

    public AnalyticsRateLimiter(
            StringRedisTemplate redisTemplate,
            AnalyticsRateLimitProperties properties,
            AnalyticsClientIpResolver clientIpResolver
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    public void check(HttpServletRequest request, String visitorId, int eventCount) {
        if (!properties.isEnabled() || eventCount < 1) {
            return;
        }
        Duration window = properties.effectiveWindow();
        long windowMillis = window.toMillis();
        long now = System.currentTimeMillis();
        long bucket = now / windowMillis;
        String clientIp = clientIpResolver.resolve(request);
        String normalizedVisitor = visitorId == null ? "unknown" : visitorId.toLowerCase(Locale.ROOT);
        List<String> keys = List.of(
                KEY_PREFIX + "ip:" + sha256(clientIp) + ":" + bucket,
                KEY_PREFIX + "visitor:" + sha256(normalizedVisitor) + ":" + bucket);
        try {
            Long allowed = redisTemplate.execute(
                    LIMIT_SCRIPT,
                    keys,
                    Integer.toString(eventCount),
                    Long.toString(windowMillis + 1_000L),
                    Integer.toString(properties.effectiveIpEventLimit()),
                    Integer.toString(properties.effectiveVisitorEventLimit()));
            if (Long.valueOf(0L).equals(allowed)) {
                throw new BusinessException(ErrorCode.ANALYTICS_RATE_LIMITED);
            }
            if (!Long.valueOf(1L).equals(allowed)) {
                throw new IllegalStateException("Analytics rate limiter returned no decision");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            logRedisFailure(now, windowMillis, ex);
        }
    }

    private void logRedisFailure(long now, long windowMillis, RuntimeException ex) {
        long previous = lastFailureLogAt.get();
        if (now - previous >= windowMillis && lastFailureLogAt.compareAndSet(previous, now)) {
            log.warn("Analytics rate limiter Redis check failed; allowing events for availability", ex);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
