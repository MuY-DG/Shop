package org.muybaby.shopserver.payment.config;

public record MaskedPaymentConfig(
        PaymentConfigSource source,
        boolean enabled,
        String appIdMasked,
        String mchIdMasked,
        String merchantSerialNoMasked,
        boolean apiV3KeyConfigured,
        Long privateKeyFileId,
        Long merchantCertificateFileId,
        Long wechatPublicKeyFileId
) {
}
