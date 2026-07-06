package org.muybaby.shopserver.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.wechat.mini-program")
public record WechatMiniProgramProperties(
        String appId,
        String appSecret,
        boolean mockEnabled
) {
}
