package org.muybaby.shopserver.payment.provider;

import java.time.LocalDateTime;

public record WechatPayOrderQueryResult(
        boolean paid,
        String outTradeNo,
        String transactionId,
        long amountCent,
        LocalDateTime paidAt,
        String tradeState
) {
    public static WechatPayOrderQueryResult notPaid(String outTradeNo, String tradeState) {
        return new WechatPayOrderQueryResult(false, outTradeNo, "", 0L, null, tradeState);
    }
}
