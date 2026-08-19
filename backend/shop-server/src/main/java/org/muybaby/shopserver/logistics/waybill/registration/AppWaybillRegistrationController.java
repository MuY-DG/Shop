package org.muybaby.shopserver.logistics.waybill.registration;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/orders")
public class AppWaybillRegistrationController {

    private final WechatWaybillRegistrationCoordinator coordinator;

    public AppWaybillRegistrationController(WechatWaybillRegistrationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/{id}/logistics/waybill-token")
    public ResponseEntity<ApiResponse<OrderWaybillTokenResponse>> token(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable("id") Long orderId
    ) {
        String token = coordinator.tokenForOwner(principal, orderId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new OrderWaybillTokenResponse(token)));
    }

    @PostMapping("/{id}/shipments/{shipmentId}/logistics/waybill-token")
    public ResponseEntity<ApiResponse<OrderWaybillTokenResponse>> shipmentToken(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable("id") Long orderId,
            @PathVariable Long shipmentId
    ) {
        String token = coordinator.tokenForOwner(principal, orderId, shipmentId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new OrderWaybillTokenResponse(token)));
    }
}
