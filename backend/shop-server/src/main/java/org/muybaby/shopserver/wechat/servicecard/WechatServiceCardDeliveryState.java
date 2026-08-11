package org.muybaby.shopserver.wechat.servicecard;

public enum WechatServiceCardDeliveryState {
    PENDING,
    SENDING,
    UNKNOWN,
    RECONCILING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
