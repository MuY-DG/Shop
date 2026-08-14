package org.muybaby.shopserver.wechat.servicecard.config;

import java.time.LocalDateTime;

public record WechatServiceCardConfigEntity(
        long id,
        String accountTemplateRecordId,
        String fallbackProductImage,
        String allowedImageHosts,
        boolean preferOrderSnapshotImages,
        boolean callbackEnabled,
        String callbackTokenCiphertext,
        Integer callbackTokenCipherVersion,
        String callbackTokenKeyId,
        long callbackTokenSecretRevision,
        String callbackAesKeyCiphertext,
        Integer callbackAesKeyCipherVersion,
        String callbackAesKeyKeyId,
        long callbackAesKeySecretRevision,
        long revision,
        LocalDateTime importedFromEnvAt,
        Long createdBy,
        Long updatedBy,
        LocalDateTime callbackTokenReencryptedAt,
        LocalDateTime callbackAesKeyReencryptedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    @Override
    public String toString() {
        return "WechatServiceCardConfigEntity[id=" + id
                + ", accountTemplateRecordIdConfigured="
                + (accountTemplateRecordId != null && !accountTemplateRecordId.isBlank())
                + ", fallbackProductImageConfigured="
                + (fallbackProductImage != null && !fallbackProductImage.isBlank())
                + ", allowedImageHostsConfigured="
                + (allowedImageHosts != null && !allowedImageHosts.isBlank())
                + ", preferOrderSnapshotImages=" + preferOrderSnapshotImages
                + ", callbackEnabled=" + callbackEnabled
                + ", callbackTokenCiphertext=<redacted>"
                + ", callbackAesKeyCiphertext=<redacted>"
                + ", callbackTokenSecretRevision=" + callbackTokenSecretRevision
                + ", callbackAesKeySecretRevision=" + callbackAesKeySecretRevision
                + ", revision=" + revision + "]";
    }
}
