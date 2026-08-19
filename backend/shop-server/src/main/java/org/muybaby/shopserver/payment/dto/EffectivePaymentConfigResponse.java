package org.muybaby.shopserver.payment.dto;

import java.time.LocalDateTime;

public record EffectivePaymentConfigResponse(
        Long id,
        String configName,
        String appIdMasked,
        String mchIdMasked,
        String merchantSerialNoMasked,
        boolean apiV3KeyConfigured,
        boolean privateKeyConfigured,
        String verifyMode,
        String wechatPublicKeyIdMasked,
        boolean wechatPublicKeyConfigured,
        boolean legacySecretFilesPendingImport,
        String notifyUrl,
        String refundNotifyUrl,
        boolean enabled,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
