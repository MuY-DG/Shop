package org.muybaby.shopserver.wechat;

public interface WechatMiniProgramClient {

    WechatCodeSession code2Session(String code);

    WechatPhoneInfo getPhoneNumber(String code);
}
