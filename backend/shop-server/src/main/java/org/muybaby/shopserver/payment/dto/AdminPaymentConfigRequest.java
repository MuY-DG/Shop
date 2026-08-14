package org.muybaby.shopserver.payment.dto;

public record AdminPaymentConfigRequest(
        String configName,
        String appId,
        String mchId,
        String merchantSerialNo,
        String apiV3Key,
        String privateKeyPem,
        String verifyMode,
        String wechatPublicKeyId,
        String wechatPublicKeyPem,
        String notifyUrl,
        String refundNotifyUrl
) {
    @Override
    public String toString() {
        return "AdminPaymentConfigRequest[configName=" + configName
                + ", appIdConfigured=" + hasText(appId)
                + ", mchIdConfigured=" + hasText(mchId)
                + ", merchantSerialNoConfigured=" + hasText(merchantSerialNo)
                + ", apiV3KeyConfigured=" + hasText(apiV3Key)
                + ", privateKeyPemConfigured=" + hasText(privateKeyPem)
                + ", verifyMode=" + verifyMode
                + ", wechatPublicKeyIdConfigured=" + hasText(wechatPublicKeyId)
                + ", wechatPublicKeyPemConfigured=" + hasText(wechatPublicKeyPem)
                + ", notifyUrlConfigured=" + hasText(notifyUrl)
                + ", refundNotifyUrlConfigured=" + hasText(refundNotifyUrl) + "]";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
