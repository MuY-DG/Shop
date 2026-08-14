package org.muybaby.shopserver.wechat.servicecard;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Validated
@ConfigurationProperties(prefix = "shop.wechat.service-card-2001")
public record WechatServiceCardProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean workerEnabled,
        @DefaultValue("") String accountTemplateRecordId,
        @DefaultValue("15s") @NotNull Duration delay,
        @DefaultValue("50") @Min(1) @Max(200) int batchSize,
        @DefaultValue("2m") @NotNull Duration claimTimeout,
        @DefaultValue("8") @Min(1) @Max(100) int maxAttempts,
        @DefaultValue("1m") @NotNull Duration retryBackoff,
        @DefaultValue("30m") @NotNull Duration maxRetryBackoff,
        @DefaultValue("1m") @NotNull Duration unknownRecheckInterval,
        @DefaultValue("6h") @NotNull Duration maxUnknownRecheckInterval,
        @DefaultValue("2") @Min(2) @Max(10) int notAppliedConfirmations,
        @DefaultValue("3s") @NotNull Duration connectTimeout,
        @DefaultValue("15s") @NotNull Duration readTimeout,
        @DefaultValue("1MB") @NotNull DataSize maxResponseSize,
        @DefaultValue("64KB") @NotNull DataSize maxPayloadSize,
        @DefaultValue("") String fallbackProductImage,
        @DefaultValue("false") boolean preferOrderSnapshotImages,
        @DefaultValue List<String> allowedImageHosts,
        @DefaultValue Callback callback
) {
    public WechatServiceCardProperties {
        requirePositive(delay, "delivery delay");
        requirePositive(claimTimeout, "claim timeout");
        requirePositive(retryBackoff, "retry backoff");
        requirePositive(maxRetryBackoff, "maximum retry backoff");
        requirePositive(unknownRecheckInterval, "unknown recheck interval");
        requirePositive(maxUnknownRecheckInterval, "maximum unknown recheck interval");
        if (unknownRecheckInterval.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("WeChat 2001 unknown recheck interval must be at least one minute");
        }
        if (maxUnknownRecheckInterval.compareTo(unknownRecheckInterval) < 0) {
            throw new IllegalArgumentException(
                    "WeChat 2001 maximum unknown recheck interval must not be shorter than its base"
            );
        }
        requirePositive(connectTimeout, "connect timeout");
        requirePositive(readTimeout, "read timeout");
        if (maxRetryBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("maximum retry backoff must not be shorter than retry backoff");
        }
        if (maxResponseSize == null || maxResponseSize.toBytes() <= 0
                || maxResponseSize.toBytes() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("response limit must be between 1 byte and 2 GB");
        }
        if (maxPayloadSize == null || maxPayloadSize.toBytes() <= 0
                || maxPayloadSize.toBytes() > 1024L * 1024L) {
            throw new IllegalArgumentException("payload limit must be between 1 byte and 1 MB");
        }
        allowedImageHosts = allowedImageHosts == null ? List.of() : List.copyOf(allowedImageHosts);
        callback = callback == null ? new Callback(false, "", "", Duration.ofMinutes(5)) : callback;
    }

    @Override
    public String toString() {
        return "WechatServiceCardProperties[enabled=" + enabled
                + ", workerEnabled=" + workerEnabled
                + ", accountTemplateRecordIdConfigured=" + StringUtils.hasText(accountTemplateRecordId)
                + ", delay=" + delay
                + ", batchSize=" + batchSize
                + ", claimTimeout=" + claimTimeout
                + ", maxAttempts=" + maxAttempts
                + ", retryBackoff=" + retryBackoff
                + ", maxRetryBackoff=" + maxRetryBackoff
                + ", unknownRecheckInterval=" + unknownRecheckInterval
                + ", maxUnknownRecheckInterval=" + maxUnknownRecheckInterval
                + ", notAppliedConfirmations=" + notAppliedConfirmations
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + ", maxResponseSize=" + maxResponseSize
                + ", maxPayloadSize=" + maxPayloadSize
                + ", fallbackProductImageConfigured=" + StringUtils.hasText(fallbackProductImage)
                + ", preferOrderSnapshotImages=" + preferOrderSnapshotImages
                + ", allowedImageHostsConfigured=" + !allowedImageHosts.isEmpty()
                + ", callback=" + callback + "]";
    }

    public Duration retryDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 20));
        Duration candidate;
        try {
            candidate = retryBackoff.multipliedBy(1L << exponent);
        } catch (ArithmeticException ex) {
            return maxRetryBackoff;
        }
        return candidate.compareTo(maxRetryBackoff) > 0 ? maxRetryBackoff : candidate;
    }

    public Duration setRetryDelay(int attemptCount) {
        return attemptCount >= maxAttempts ? maxRetryBackoff : retryDelay(attemptCount);
    }

    public Duration reconciliationDelay(int reconcileAttemptCount, long stableKey) {
        int exponent = Math.max(0, Math.min(reconcileAttemptCount - 1, 20));
        Duration candidate;
        try {
            candidate = unknownRecheckInterval.multipliedBy(1L << exponent);
        } catch (ArithmeticException ex) {
            candidate = maxUnknownRecheckInterval;
        }
        if (candidate.compareTo(maxUnknownRecheckInterval) > 0) {
            candidate = maxUnknownRecheckInterval;
        }
        if (reconcileAttemptCount <= 1 || candidate.equals(maxUnknownRecheckInterval)) {
            return candidate;
        }
        int percent = 90 + Math.floorMod(
                Long.hashCode(stableKey) * 31 + reconcileAttemptCount, 21
        );
        long jitteredMillis;
        try {
            jitteredMillis = Math.multiplyExact(candidate.toMillis(), percent) / 100L;
        } catch (ArithmeticException ex) {
            return maxUnknownRecheckInterval;
        }
        Duration jittered = Duration.ofMillis(Math.max(
                unknownRecheckInterval.toMillis(), jitteredMillis
        ));
        return jittered.compareTo(maxUnknownRecheckInterval) > 0
                ? maxUnknownRecheckInterval : jittered;
    }

    public int maxResponseBytes() {
        return Math.toIntExact(maxResponseSize.toBytes());
    }

    public int maxPayloadBytes() {
        return Math.toIntExact(maxPayloadSize.toBytes());
    }

    public Set<String> normalizedAllowedImageHosts() {
        return allowedImageHosts.stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean imageConfigurationReady() {
        return validPublicImage(fallbackProductImage, normalizedAllowedImageHosts());
    }

    public boolean templateConfigurationReady() {
        return StringUtils.hasText(accountTemplateRecordId)
                && accountTemplateRecordId.trim().length() <= 128;
    }

    public static boolean validPublicImage(String value, Set<String> allowedHosts) {
        if (!StringUtils.hasText(value) || allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && StringUtils.hasText(uri.getHost())
                    && allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && !StringUtils.hasText(uri.getQuery())
                    && !StringUtils.hasText(uri.getFragment());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("WeChat 2001 " + name + " must be positive");
        }
    }

    public record Callback(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token,
            @DefaultValue("") String encodingAesKey,
            @DefaultValue("5m") Duration maxTimestampSkew
    ) {
        public Callback {
            maxTimestampSkew = maxTimestampSkew == null ? Duration.ofMinutes(5) : maxTimestampSkew;
            requirePositive(maxTimestampSkew, "callback timestamp skew");
        }

        public boolean secureReady() {
            String normalizedToken = token == null ? "" : token.trim();
            String normalizedAesKey = encodingAesKey == null ? "" : encodingAesKey.trim();
            if (!enabled
                    || !normalizedToken.matches("[A-Za-z0-9]{3,32}")
                    || !normalizedAesKey.matches("[A-Za-z0-9]{43}")) {
                return false;
            }
            try {
                return java.util.Base64.getDecoder()
                        .decode(normalizedAesKey + "=").length == 32;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "Callback[enabled=" + enabled
                    + ", token=<redacted>"
                    + ", encodingAesKey=<redacted>"
                    + ", maxTimestampSkew=" + maxTimestampSkew + "]";
        }
    }
}
