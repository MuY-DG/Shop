package org.muybaby.shopserver.wechat.servicecard.config.dto;

import java.util.List;

public record AdminWechatServiceCardConfigUpdateRequest(
        String accountTemplateRecordId,
        String fallbackProductImage,
        List<String> allowedImageHosts,
        Boolean preferOrderSnapshotImages,
        Boolean callbackEnabled,
        String callbackToken,
        String callbackEncodingAesKey,
        Long version
) {

    @Override
    public String toString() {
        return "AdminWechatServiceCardConfigUpdateRequest[accountTemplateRecordIdConfigured="
                + (accountTemplateRecordId != null && !accountTemplateRecordId.isBlank())
                + ", fallbackProductImageConfigured="
                + (fallbackProductImage != null && !fallbackProductImage.isBlank())
                + ", allowedImageHostsCount="
                + (allowedImageHosts == null ? 0 : allowedImageHosts.size())
                + ", preferOrderSnapshotImages=" + preferOrderSnapshotImages
                + ", callbackEnabled=" + callbackEnabled
                + ", callbackToken=<redacted>, callbackEncodingAesKey=<redacted>"
                + ", version=" + version + "]";
    }
}
