package org.muybaby.shopserver.payment;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.payment.service.PaymentCallbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WechatPayCallbackController {

    private final PaymentCallbackService paymentCallbackService;

    public WechatPayCallbackController(PaymentCallbackService paymentCallbackService) {
        this.paymentCallbackService = paymentCallbackService;
    }

    @PostMapping("/wxpay/pay/notify")
    public ApiResponse<Void> payNotify(
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody String body
    ) {
        paymentCallbackService.handlePayNotification(timestamp, nonce, serial, signature, body);
        return ApiResponse.success();
    }
}
