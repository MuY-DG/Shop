package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.WechatReceiptQueryStatus;

import java.util.Objects;

public record WechatReceiptQueryResult(
        WechatReceiptQueryStatus status,
        Integer orderState,
        String errorCode,
        String errorMessage
) {
    public WechatReceiptQueryResult {
        Objects.requireNonNull(status, "status");
    }

    public boolean confirmed() {
        return status == WechatReceiptQueryStatus.CONFIRMED;
    }

    public static WechatReceiptQueryResult confirmed(int orderState) {
        return new WechatReceiptQueryResult(
                WechatReceiptQueryStatus.CONFIRMED, orderState, null, null
        );
    }

    public static WechatReceiptQueryResult notConfirmed(int orderState) {
        return new WechatReceiptQueryResult(
                WechatReceiptQueryStatus.NOT_CONFIRMED, orderState, null, null
        );
    }

    public static WechatReceiptQueryResult unavailable(String errorCode, String errorMessage) {
        return new WechatReceiptQueryResult(
                WechatReceiptQueryStatus.UNAVAILABLE, null, errorCode, errorMessage
        );
    }

    public static WechatReceiptQueryResult unknown(String errorCode, String errorMessage) {
        return new WechatReceiptQueryResult(
                WechatReceiptQueryStatus.UNKNOWN, null, errorCode, errorMessage
        );
    }
}
