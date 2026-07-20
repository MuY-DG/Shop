package org.muybaby.shopserver.aftersale;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.pay.refund-recovery")
public record RefundRecoveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") @NotNull Duration delay,
        @DefaultValue("20") @Min(1) @Max(200) int batchSize,
        @DefaultValue("1m") @NotNull Duration minAge,
        @DefaultValue("5m") @NotNull Duration claimTimeout,
        @DefaultValue("1m") @NotNull Duration baseRetryDelay,
        @DefaultValue("30m") @NotNull Duration maxRetryDelay
) {

    public RefundRecoveryProperties {
        requirePositive(delay, "Refund recovery delay");
        requirePositive(minAge, "Refund recovery minimum age");
        requirePositive(claimTimeout, "Refund recovery claim timeout");
        requirePositive(baseRetryDelay, "Refund recovery base retry delay");
        requirePositive(maxRetryDelay, "Refund recovery maximum retry delay");
        if (baseRetryDelay != null && maxRetryDelay != null && baseRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException("Refund recovery base retry delay cannot exceed maximum retry delay");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
