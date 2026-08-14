package org.muybaby.shopserver.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.wechat.mini-program")
public record WechatMiniProgramProperties(
        String appId,
        String appSecret,
        boolean mockEnabled
) {

    @Override
    public String toString() {
        return "WechatMiniProgramProperties[appIdConfigured=" + configured(appId)
                + ", appSecret=<redacted>"
                + ", mockEnabled=" + mockEnabled + "]";
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
