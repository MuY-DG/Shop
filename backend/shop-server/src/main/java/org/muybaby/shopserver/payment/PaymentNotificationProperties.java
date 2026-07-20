package org.muybaby.shopserver.payment;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.pay.notification")
public record PaymentNotificationProperties(
        @DefaultValue("5m") @NotNull Duration maxTimestampSkew
) {

    private static final Duration MAX_CONFIGURABLE_SKEW = Duration.ofHours(1);

    public PaymentNotificationProperties {
        if (maxTimestampSkew == null || maxTimestampSkew.isZero() || maxTimestampSkew.isNegative()) {
            throw new IllegalArgumentException("Payment notification timestamp skew must be positive");
        }
        if (maxTimestampSkew.compareTo(MAX_CONFIGURABLE_SKEW) > 0) {
            throw new IllegalArgumentException("Payment notification timestamp skew cannot exceed one hour");
        }
    }
}
