package org.muybaby.shopserver.wechat;

public record WechatCodeSession(String openid, String unionid, String sessionKey) {
}
