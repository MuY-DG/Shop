package org.muybaby.shopserver.wechat.servicecard.provider;

public record WechatServiceCardSetRequest(
        String openid,
        String notifyCode,
        String contentJson,
        String checkJson
) {
}
