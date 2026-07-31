package org.muybaby.shopserver.payment;

import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.pay")
public record PaymentProperties(
        boolean enabled,
        Boolean mockEnabled,
        PaymentConfigSource configSource,
        String appId,
        String mchId,
        String merchantSerialNo,
        String privateKeyPath,
        String apiV3Key,
        String notifyUrl,
        String refundNotifyUrl,
        PaymentVerifyMode verifyMode,
        String publicKeyId,
        String publicKeyPath,
        Integer expireMinutes
) {
}
