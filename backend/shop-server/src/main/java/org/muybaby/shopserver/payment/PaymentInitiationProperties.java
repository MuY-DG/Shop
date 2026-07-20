package org.muybaby.shopserver.payment;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "shop.pay.initiation")
public record PaymentInitiationProperties(
        @DefaultValue("2m") @NotNull Duration claimTimeout
) {

    public PaymentInitiationProperties {
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            throw new IllegalArgumentException("Payment initiation claim timeout must be positive");
        }
    }
}
