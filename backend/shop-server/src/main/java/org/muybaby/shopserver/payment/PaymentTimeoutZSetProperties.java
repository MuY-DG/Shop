package org.muybaby.shopserver.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.pay.timeout-zset")
public record PaymentTimeoutZSetProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") @NotNull Duration pollDelay,
        @DefaultValue("50") @Min(1) @Max(500) int batchSize,
        @DefaultValue("30s") @NotNull Duration retryDelay
) {

    public PaymentTimeoutZSetProperties {
        requirePositive(pollDelay, "Payment timeout ZSet poll delay");
        requirePositive(retryDelay, "Payment timeout ZSet retry delay");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
