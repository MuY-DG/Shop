package org.muybaby.shopserver.wechat.servicecard.config.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public record AdminWechatServiceCardConfigResponse(
        boolean configured,
        String source,
        String accountTemplateRecordId,
        String fallbackProductImage,
        List<String> allowedImageHosts,
        boolean preferOrderSnapshotImages,
        boolean callbackEnabled,
        String callbackTokenMasked,
        boolean callbackTokenConfigured,
        String callbackEncodingAesKeyMasked,
        boolean callbackEncodingAesKeyConfigured,
        boolean legacyEnvironmentImportAvailable,
        long version,
        @JsonStringId Long updatedBy,
        LocalDateTime updatedAt
) {
}
