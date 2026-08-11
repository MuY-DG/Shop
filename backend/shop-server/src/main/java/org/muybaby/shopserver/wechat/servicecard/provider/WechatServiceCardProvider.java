package org.muybaby.shopserver.wechat.servicecard.provider;

public interface WechatServiceCardProvider {

    WechatServiceCardSetResult setUserNotify(WechatServiceCardSetRequest request);

    WechatServiceCardQueryResult getUserNotify(WechatServiceCardQueryRequest request);
}
