package org.muybaby.shopserver.payment.dto;

public record AdminPaymentConfigRequest(
        String configName,
        String appId,
        String mchId,
        String merchantSerialNo,
        String apiV3Key,
        Long privateKeyFileId,
        Long merchantCertificateFileId,
        String verifyMode,
        String wechatPublicKeyId,
        Long wechatPublicKeyFileId,
        String notifyUrl,
        String refundNotifyUrl
) {
}
