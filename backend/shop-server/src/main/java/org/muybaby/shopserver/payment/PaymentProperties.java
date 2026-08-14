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
    @Override
    public String toString() {
        return "PaymentProperties[enabled=" + enabled
                + ", mockEnabled=" + mockEnabled
                + ", configSource=" + configSource
                + ", appIdConfigured=" + hasText(appId)
                + ", mchIdConfigured=" + hasText(mchId)
                + ", merchantSerialNoConfigured=" + hasText(merchantSerialNo)
                + ", privateKeyPathConfigured=" + hasText(privateKeyPath)
                + ", apiV3KeyConfigured=" + hasText(apiV3Key)
                + ", notifyUrlConfigured=" + hasText(notifyUrl)
                + ", refundNotifyUrlConfigured=" + hasText(refundNotifyUrl)
                + ", verifyMode=" + verifyMode
                + ", publicKeyIdConfigured=" + hasText(publicKeyId)
                + ", publicKeyPathConfigured=" + hasText(publicKeyPath)
                + ", expireMinutes=" + expireMinutes + "]";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
