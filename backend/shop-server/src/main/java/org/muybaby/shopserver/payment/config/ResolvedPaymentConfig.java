package org.muybaby.shopserver.payment.config;

public record ResolvedPaymentConfig(
        PaymentConfigSource source,
        boolean enabled,
        String appId,
        String mchId,
        String merchantSerialNo,
        String apiV3Key,
        String privateKeyPem,
        String notifyUrl,
        String refundNotifyUrl,
        PaymentVerifyMode verifyMode,
        String wechatPublicKeyId,
        String wechatPublicKeyPem,
        Long privateKeyFileId,
        Long merchantCertificateFileId,
        Long wechatPublicKeyFileId
) {
}
