package org.muybaby.shopserver.wechat;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RestWechatMiniProgramClient implements WechatMiniProgramClient {

    @SuppressWarnings("unused")
    private final WechatMiniProgramProperties properties;

    public RestWechatMiniProgramClient(WechatMiniProgramProperties properties) {
        this.properties = properties;
    }

    @Override
    public WechatCodeSession code2Session(String code) {
        throw new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
    }

    @Override
    public WechatPhoneInfo getPhoneNumber(String code) {
        throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
    }
}
