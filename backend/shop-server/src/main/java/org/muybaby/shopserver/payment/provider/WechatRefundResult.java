package org.muybaby.shopserver.payment.provider;

public record WechatRefundResult(
        String outRefundNo,
        String refundId,
        String status
) {
}
