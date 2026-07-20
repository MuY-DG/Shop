package org.muybaby.shopserver.payment.provider;

import java.time.LocalDateTime;

public record WechatRefundQueryResult(
        String outRefundNo,
        String refundId,
        String outTradeNo,
        String status,
        long refundAmountCent,
        LocalDateTime successAt
) {
}
