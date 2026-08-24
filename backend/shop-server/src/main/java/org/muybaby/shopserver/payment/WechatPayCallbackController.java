package org.muybaby.shopserver.payment;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.aftersale.service.RefundCallbackService;
import org.muybaby.shopserver.payment.service.PaymentCallbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WechatPayCallbackController {

    private final PaymentCallbackService paymentCallbackService;
    private final RefundCallbackService refundCallbackService;

    public WechatPayCallbackController(
            PaymentCallbackService paymentCallbackService,
            RefundCallbackService refundCallbackService
    ) {
        this.paymentCallbackService = paymentCallbackService;
        this.refundCallbackService = refundCallbackService;
    }

    @PostMapping("/wxpay/pay/notify/r/{routeToken}")
    public ApiResponse<Void> routedPayNotify(
            @PathVariable String routeToken,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody String body
    ) {
        paymentCallbackService.handlePayNotification(
                routeToken, timestamp, nonce, serial, signature, body);
        return ApiResponse.success();
    }

    @PostMapping("/wxpay/refund/notify/r/{routeToken}")
    public ApiResponse<Void> routedRefundNotify(
            @PathVariable String routeToken,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody String body
    ) {
        refundCallbackService.handleRefundNotification(
                routeToken, timestamp, nonce, serial, signature, body);
        return ApiResponse.success();
    }
}
