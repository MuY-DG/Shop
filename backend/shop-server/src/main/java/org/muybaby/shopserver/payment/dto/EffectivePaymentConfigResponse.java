package org.muybaby.shopserver.payment.dto;

import java.time.LocalDateTime;

public record EffectivePaymentConfigResponse(
        Long id,
        String source,
        String configName,
        String appIdMasked,
        String mchIdMasked,
        String merchantSerialNoMasked,
        boolean apiV3KeyConfigured,
        Long privateKeyFileId,
        Long merchantCertificateFileId,
        String verifyMode,
        String wechatPublicKeyIdMasked,
        Long wechatPublicKeyFileId,
        String notifyUrl,
        String refundNotifyUrl,
        boolean enabled,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
