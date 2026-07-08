package org.muybaby.shopserver.payment.provider;

public record WechatRefundRequest(
        String outTradeNo,
        String transactionId,
        String outRefundNo,
        long refundAmountCent,
        long totalAmountCent,
        String reason,
        String notifyUrl
) {
}
