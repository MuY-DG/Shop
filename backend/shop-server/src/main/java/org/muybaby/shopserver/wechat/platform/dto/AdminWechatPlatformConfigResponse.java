package org.muybaby.shopserver.wechat.platform.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminWechatPlatformConfigResponse(
        boolean configured,
        String source,
        String appId,
        String appSecretMasked,
        boolean appSecretConfigured,
        long version,
        @JsonStringId Long updatedBy,
        LocalDateTime updatedAt
) {
}
