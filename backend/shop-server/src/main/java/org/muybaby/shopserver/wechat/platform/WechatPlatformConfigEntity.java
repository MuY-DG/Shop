package org.muybaby.shopserver.wechat.platform;

import java.time.LocalDateTime;

public record WechatPlatformConfigEntity(
        long id,
        String appId,
        String appSecretCiphertext,
        int secretCipherVersion,
        String secretKeyId,
        long secretRevision,
        long revision,
        Long createdBy,
        Long updatedBy,
        LocalDateTime secretReencryptedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    @Override
    public String toString() {
        return "WechatPlatformConfigEntity[id=" + id
                + ", secretCipherVersion=" + secretCipherVersion
                + ", secretKeyId=" + secretKeyId
                + ", secretRevision=" + secretRevision
                + ", revision=" + revision + "]";
    }
}
