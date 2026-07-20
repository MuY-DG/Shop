package org.muybaby.shopserver.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.pay")
public record PaymentTimeoutScanProperties(
        @DefaultValue("true") boolean timeoutScanEnabled,
        @DefaultValue("60s") @NotNull Duration timeoutScanDelay,
        @DefaultValue("50") @Min(1) @Max(500) int timeoutScanBatchSize,
        @DefaultValue("5m") @NotNull Duration timeoutScanClaimTimeout
) {

    public PaymentTimeoutScanProperties {
        requirePositive(timeoutScanDelay, "Payment timeout scan delay");
        requirePositive(timeoutScanClaimTimeout, "Payment timeout claim timeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
