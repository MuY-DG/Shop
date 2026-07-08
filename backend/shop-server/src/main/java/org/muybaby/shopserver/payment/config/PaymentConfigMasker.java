package org.muybaby.shopserver.payment.config;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PaymentConfigMasker {

    public MaskedPaymentConfig mask(ResolvedPaymentConfig config) {
        return new MaskedPaymentConfig(
                config.source(),
                config.enabled(),
                mask(config.appId(), 3, 3),
                mask(config.mchId(), 2, 2),
                mask(config.merchantSerialNo(), 3, 3),
                StringUtils.hasText(config.apiV3Key()),
                config.privateKeyFileId(),
                config.merchantCertificateFileId(),
                config.wechatPublicKeyFileId()
        );
    }

    private String mask(String value, int prefixLength, int suffixLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= prefixLength + suffixLength) {
            return "*".repeat(value.length());
        }
        int starCount = value.length() > 10 ? 6 : 3;
        return value.substring(0, prefixLength)
                + "*".repeat(starCount)
                + value.substring(value.length() - suffixLength);
    }
}
