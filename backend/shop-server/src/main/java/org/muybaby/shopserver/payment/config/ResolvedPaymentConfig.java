package org.muybaby.shopserver.payment.config;

public record ResolvedPaymentConfig(
        PaymentConfigSource source,
        Long configId,
        String configName,
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
        String wechatPublicKeyPem
) {
    @Override
    public String toString() {
        return "ResolvedPaymentConfig[source=" + source
                + ", configId=" + configId
                + ", configName=" + configName
                + ", enabled=" + enabled
                + ", appIdConfigured=" + hasText(appId)
                + ", mchIdConfigured=" + hasText(mchId)
                + ", merchantSerialNoConfigured=" + hasText(merchantSerialNo)
                + ", apiV3KeyConfigured=" + hasText(apiV3Key)
                + ", privateKeyPemConfigured=" + hasText(privateKeyPem)
                + ", notifyUrlConfigured=" + hasText(notifyUrl)
                + ", refundNotifyUrlConfigured=" + hasText(refundNotifyUrl)
                + ", verifyMode=" + verifyMode
                + ", wechatPublicKeyIdConfigured=" + hasText(wechatPublicKeyId)
                + ", wechatPublicKeyPemConfigured=" + hasText(wechatPublicKeyPem) + "]";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
