package org.muybaby.shopserver.logistics.provider;

public record WechatShippingUploadRequest(
        Long orderId,
        String transactionId,
        String outTradeNo,
        String openid,
        String expressCompany,
        String trackingNo,
        String shipmentNote
) {
}
