package org.muybaby.shopserver.logistics.provider;

import java.util.Objects;

public record WechatShippingOrderQueryResult(
        WechatShippingOrderQueryStatus status,
        String transactionId,
        Integer orderState,
        WechatShippingSummary shipping,
        String errorCode,
        String errorMessage
) {
    public WechatShippingOrderQueryResult {
        Objects.requireNonNull(status, "status");
    }

    public static WechatShippingOrderQueryResult uploaded(
            String transactionId,
            int orderState,
            WechatShippingSummary shipping
    ) {
        return new WechatShippingOrderQueryResult(
                WechatShippingOrderQueryStatus.UPLOADED,
                transactionId,
                orderState,
                shipping,
                null,
                null
        );
    }

    public static WechatShippingOrderQueryResult notUploaded(String transactionId, int orderState) {
        return new WechatShippingOrderQueryResult(
                WechatShippingOrderQueryStatus.NOT_UPLOADED,
                transactionId,
                orderState,
                null,
                null,
                null
        );
    }

    public static WechatShippingOrderQueryResult unavailable(String errorCode, String errorMessage) {
        return new WechatShippingOrderQueryResult(
                WechatShippingOrderQueryStatus.UNAVAILABLE,
                null,
                null,
                null,
                errorCode,
                errorMessage
        );
    }

    public static WechatShippingOrderQueryResult unknown(String errorCode, String errorMessage) {
        return unknown(null, null, null, errorCode, errorMessage);
    }

    public static WechatShippingOrderQueryResult unknown(
            String transactionId,
            Integer orderState,
            WechatShippingSummary shipping,
            String errorCode,
            String errorMessage
    ) {
        return new WechatShippingOrderQueryResult(
                WechatShippingOrderQueryStatus.UNKNOWN,
                transactionId,
                orderState,
                shipping,
                errorCode,
                errorMessage
        );
    }
}
