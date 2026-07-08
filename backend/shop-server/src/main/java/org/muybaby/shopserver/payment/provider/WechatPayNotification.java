package org.muybaby.shopserver.payment.provider;

import java.time.LocalDateTime;

public record WechatPayNotification(
        String notifyId,
        String eventType,
        String outTradeNo,
        String transactionId,
        long amountCent,
        String currency,
        LocalDateTime paidAt,
        String resourceDigest
) {
}
