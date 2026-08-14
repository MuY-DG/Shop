package org.muybaby.shopserver.wechat.platform;

public record WechatPlatformCredentials(
        String appId,
        String appSecret,
        Source source
) {

    @Override
    public String toString() {
        return "WechatPlatformCredentials[appIdConfigured="
                + (appId != null && !appId.isBlank())
                + ", appSecretConfigured="
                + (appSecret != null && !appSecret.isBlank())
                + ", source=" + source + "]";
    }

    public enum Source {
        DATABASE,
        ENVIRONMENT
    }
}
