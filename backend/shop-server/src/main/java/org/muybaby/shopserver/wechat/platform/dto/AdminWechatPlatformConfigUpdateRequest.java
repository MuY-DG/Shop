package org.muybaby.shopserver.wechat.platform.dto;

public record AdminWechatPlatformConfigUpdateRequest(
        String appId,
        String appSecret,
        Long version
) {

    @Override
    public String toString() {
        return "AdminWechatPlatformConfigUpdateRequest[appIdConfigured="
                + (appId != null && !appId.isBlank())
                + ", appSecretConfigured="
                + (appSecret != null && !appSecret.isBlank())
                + ", version=" + version + "]";
    }
}
