package org.muybaby.shopserver.payment.provider;

public record WechatJsapiPrepayResult(
        String prepayId,
        String timeStamp,
        String nonceStr,
        String packageValue,
        String signType,
        String paySign
) {
}
