package org.muybaby.shopserver.logistics.tracking;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.logistics.tracking.dto.ShipmentTrackingResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/orders")
public class AppShipmentTrackingController {

    private final ShipmentTrackingCoordinator coordinator;

    public AppShipmentTrackingController(ShipmentTrackingCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/{id}/logistics/tracking")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> tracking(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable("id") long orderId
    ) {
        return noStore(coordinator.readForOwner(principal, orderId));
    }

    @GetMapping("/{id}/shipments/{shipmentId}/logistics/tracking")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> shipmentTracking(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable("id") long orderId,
            @PathVariable long shipmentId
    ) {
        return noStore(coordinator.readForOwner(principal, orderId, shipmentId));
    }

    @PostMapping("/{id}/logistics/tracking/sync")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> sync(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable("id") long orderId
    ) {
        return noStore(coordinator.syncForOwner(principal, orderId));
    }

    @PostMapping("/{id}/shipments/{shipmentId}/logistics/tracking/sync")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> syncShipment(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable("id") long orderId,
            @PathVariable long shipmentId
    ) {
        return noStore(coordinator.syncForOwner(principal, orderId, shipmentId));
    }

    private ResponseEntity<ApiResponse<ShipmentTrackingResponse>> noStore(
            ShipmentTrackingResponse response
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}
