package org.muybaby.shopserver.payment.provider;

import java.time.LocalDateTime;

public record WechatJsapiPrepayRequest(
        String description,
        String outTradeNo,
        long amountCent,
        String currency,
        String payerOpenid,
        String notifyUrl,
        LocalDateTime timeExpire
) {
}
