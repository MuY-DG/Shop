package org.muybaby.shopserver.wechat.servicecard.provider;

public record WechatServiceCardQueryRequest(String openid, String notifyCode) {
}
