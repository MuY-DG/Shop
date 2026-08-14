package org.muybaby.shopserver.wechat.platform;

public interface WechatPlatformCredentialResolver {

    WechatPlatformCredentials resolve();

    default boolean readyFailClosed() {
        try {
            WechatPlatformCredentials credentials = resolve();
            return credentials != null
                    && credentials.appId() != null && !credentials.appId().isBlank()
                    && credentials.appSecret() != null && !credentials.appSecret().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
