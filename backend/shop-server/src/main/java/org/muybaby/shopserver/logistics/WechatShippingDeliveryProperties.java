package org.muybaby.shopserver.logistics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.wechat.shipping.delivery")
public record WechatShippingDeliveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("15s") @NotNull Duration delay,
        @DefaultValue("50") @Min(1) @Max(200) int batchSize,
        @DefaultValue("1m") @NotNull Duration claimTimeout,
        @DefaultValue("8") @Min(1) @Max(100) int maxAttempts,
        @DefaultValue("30s") @NotNull Duration retryBackoff,
        @DefaultValue("30m") @NotNull Duration maxRetryBackoff,
        @DefaultValue("1m") @NotNull Duration unknownRecheckInterval,
        @DefaultValue("2") @Min(2) @Max(10) int notUploadedConfirmations
) {

    public WechatShippingDeliveryProperties {
        requirePositive(delay, "WeChat shipping delivery delay");
        requirePositive(claimTimeout, "WeChat shipping delivery claim timeout");
        requirePositive(retryBackoff, "WeChat shipping retry backoff");
        requirePositive(maxRetryBackoff, "WeChat shipping maximum retry backoff");
        requirePositive(unknownRecheckInterval, "WeChat shipping unknown recheck interval");
        if (maxRetryBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "WeChat shipping maximum retry backoff must not be shorter than the initial backoff"
            );
        }
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

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
