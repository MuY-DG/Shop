package org.muybaby.shopserver.payment;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.payment.dto.PaymentCancelResponse;
import org.muybaby.shopserver.payment.dto.PaymentSyncResponse;
import org.muybaby.shopserver.payment.dto.WechatPaymentParamsResponse;
import org.muybaby.shopserver.payment.service.AppPaymentService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AppPaymentController {

    private final AppPaymentService appPaymentService;

    public AppPaymentController(AppPaymentService appPaymentService) {
        this.appPaymentService = appPaymentService;
    }

    @PostMapping("/app/orders/{orderId}/pay")
    public ApiResponse<WechatPaymentParamsResponse> pay(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appPaymentService.pay(principal, orderId));
    }

    @PostMapping("/app/orders/{orderId}/cancel")
    public ApiResponse<PaymentCancelResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appPaymentService.cancel(principal, orderId));
    }

    @PostMapping("/app/orders/{orderId}/payment/sync")
    public ApiResponse<PaymentSyncResponse> sync(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appPaymentService.sync(principal, orderId));
    }
}
