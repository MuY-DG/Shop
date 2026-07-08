package org.muybaby.shopserver.payment.provider;

import java.time.LocalDateTime;

public record WechatRefundNotification(
        String notifyId,
        String outTradeNo,
        String outRefundNo,
        String refundId,
        String status,
        long refundAmountCent,
        LocalDateTime successAt,
        String resourceDigest
) {
}
