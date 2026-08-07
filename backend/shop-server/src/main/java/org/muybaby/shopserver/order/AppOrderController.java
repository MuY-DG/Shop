package org.muybaby.shopserver.order;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.order.dto.AppOrderPreviewRequest;
import org.muybaby.shopserver.order.dto.AppOrderSubmitRequest;
import org.muybaby.shopserver.order.dto.AppOrderDetailResponse;
import org.muybaby.shopserver.order.dto.AppOrderReceiverUpdateRequest;
import org.muybaby.shopserver.order.dto.OrderPreviewResponse;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.order.dto.OrderReceiverUpdateResponse;
import org.muybaby.shopserver.order.dto.OrderSubmitResponse;
import org.muybaby.shopserver.order.dto.OrderSummaryResponse;
import org.muybaby.shopserver.order.service.AppOrderService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/app/orders")
public class AppOrderController {

    private final AppOrderService appOrderService;

    public AppOrderController(AppOrderService appOrderService) {
        this.appOrderService = appOrderService;
    }

    @PostMapping("/preview")
    public ApiResponse<OrderPreviewResponse> preview(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody(required = false) AppOrderPreviewRequest request
    ) {
        return ApiResponse.success(appOrderService.preview(principal, request));
    }

    @PostMapping
    public ApiResponse<OrderSubmitResponse> submit(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AppOrderSubmitRequest request
    ) {
        return ApiResponse.success(appOrderService.submit(principal, request));
    }

    @GetMapping
    public ApiResponse<PageResult<OrderSummaryResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status,
            OrderStatusGroup statusGroup,
            String keyword
    ) {
        return ApiResponse.success(appOrderService.list(principal, current, size, status, statusGroup, keyword));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<AppOrderDetailResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appOrderService.detail(principal, orderId));
    }

    @DeleteMapping("/{orderId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        appOrderService.deleteFinished(principal, orderId);
        return ApiResponse.success();
    }

    @PutMapping("/{orderId}/receiver")
    public ApiResponse<OrderReceiverUpdateResponse> updateReceiver(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody AppOrderReceiverUpdateRequest request
    ) {
        return ApiResponse.success(appOrderService.updateReceiver(principal, orderId, request));
    }

    @PostMapping("/{orderId}/confirm-receipt")
    public ApiResponse<OrderReceiptResponse> confirmReceipt(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appOrderService.confirmReceipt(principal, orderId));
    }
}
