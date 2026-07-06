package org.muybaby.shopserver.wechat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "true")
public class MockWechatMiniProgramClient implements WechatMiniProgramClient {

    @Override
    public WechatCodeSession code2Session(String code) {
        return new WechatCodeSession("test-openid-" + code, null, "test-session-key");
    }

    @Override
    public WechatPhoneInfo getPhoneNumber(String code) {
        return new WechatPhoneInfo("13812345678", "13812345678", "86");
    }
}
