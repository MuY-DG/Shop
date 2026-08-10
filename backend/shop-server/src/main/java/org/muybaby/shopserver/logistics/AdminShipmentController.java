package org.muybaby.shopserver.logistics;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.service.AdminShipmentService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
public class AdminShipmentController {

    private final AdminShipmentService adminShipmentService;

    public AdminShipmentController(AdminShipmentService adminShipmentService) {
        this.adminShipmentService = adminShipmentService;
    }

    @PostMapping("/{orderId}/ship")
    @PreAuthorize("hasAuthority('order:ship')")
    public ApiResponse<OrderShipmentResponse> ship(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody AdminShipOrderRequest request
    ) {
        return ApiResponse.success(adminShipmentService.ship(principal, orderId, request));
    }

    @PostMapping("/{orderId}/shipping/retry-wechat-upload")
    @PreAuthorize("hasAuthority('order:shipping:retry')")
    public ApiResponse<OrderShipmentResponse> retryWechatUpload(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(adminShipmentService.retryWechatUpload(principal, orderId));
    }

    @PostMapping("/{orderId}/shipping/reconcile-wechat-upload")
    @PreAuthorize("hasAuthority('order:shipping:retry')")
    public ApiResponse<OrderShipmentResponse> reconcileWechatUpload(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(adminShipmentService.reconcileWechatUpload(principal, orderId));
    }

    @PostMapping("/{orderId}/shipping/retry-waybill-registration")
    @PreAuthorize("hasAuthority('order:shipping:registration:retry')")
    public ApiResponse<OrderShipmentResponse> retryWaybillRegistration(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(adminShipmentService.retryWaybillRegistration(principal, orderId));
    }

    @PostMapping("/{orderId}/waybills/{waybillRecordId}/confirm-shipment")
    @PreAuthorize("hasAuthority('order:waybill:manage') and hasAuthority('order:ship')")
    public ApiResponse<OrderShipmentResponse> confirmElectronicWaybill(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable Long waybillRecordId
    ) {
        return ApiResponse.success(adminShipmentService.confirmElectronicWaybill(
                principal, orderId, waybillRecordId
        ));
    }
}
