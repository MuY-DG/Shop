package org.muybaby.shopserver.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WechatPaymentParamsResponse(
        String timeStamp,
        String nonceStr,
        @JsonProperty("package")
        String packageValue,
        String signType,
        String paySign
) {
}
