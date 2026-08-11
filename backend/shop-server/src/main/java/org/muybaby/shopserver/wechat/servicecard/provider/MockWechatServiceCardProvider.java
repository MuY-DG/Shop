package org.muybaby.shopserver.wechat.servicecard.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatServiceCardProvider implements WechatServiceCardProvider {
    @Override
    public WechatServiceCardSetResult setUserNotify(WechatServiceCardSetRequest request) {
        return WechatServiceCardSetResult.retryable(null, "Mock provider does not send WeChat service cards");
    }

    @Override
    public WechatServiceCardQueryResult getUserNotify(WechatServiceCardQueryRequest request) {
        return WechatServiceCardQueryResult.retryable(null, "Mock provider cannot query WeChat service cards");
    }
}
