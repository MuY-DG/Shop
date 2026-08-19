package org.muybaby.shopserver.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.pay")
public record PaymentProperties(
        Boolean mockEnabled,
        Integer expireMinutes
) {
}
