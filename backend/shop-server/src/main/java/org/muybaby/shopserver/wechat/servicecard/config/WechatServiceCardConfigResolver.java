package org.muybaby.shopserver.wechat.servicecard.config;

import java.util.Optional;

public interface WechatServiceCardConfigResolver {

    WechatServiceCardConfig resolve();

    default Optional<WechatServiceCardConfig> resolveFailClosed() {
        try {
            return Optional.of(resolve());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
