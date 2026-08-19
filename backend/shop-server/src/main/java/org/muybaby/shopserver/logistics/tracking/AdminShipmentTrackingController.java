package org.muybaby.shopserver.logistics.tracking;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.logistics.tracking.dto.ShipmentTrackingResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
public class AdminShipmentTrackingController {

    private final ShipmentTrackingCoordinator coordinator;

    public AdminShipmentTrackingController(ShipmentTrackingCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @GetMapping("/{orderId}/shipping/tracking")
    @PreAuthorize("hasAuthority('order:read')")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> tracking(
            @PathVariable long orderId
    ) {
        return noStore(coordinator.readForAdmin(orderId));
    }

    @GetMapping("/{orderId}/shipments/{shipmentId}/tracking")
    @PreAuthorize("hasAuthority('order:read')")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> shipmentTracking(
            @PathVariable long orderId,
            @PathVariable long shipmentId
    ) {
        return noStore(coordinator.readForAdmin(orderId, shipmentId));
    }

    @PostMapping("/{orderId}/shipping/tracking/sync")
    @PreAuthorize("hasAuthority('order:shipping:tracking:sync')")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> sync(
            @PathVariable long orderId
    ) {
        return noStore(coordinator.syncForAdmin(orderId));
    }

    @PostMapping("/{orderId}/shipments/{shipmentId}/tracking/sync")
    @PreAuthorize("hasAuthority('order:shipping:tracking:sync')")
    public ResponseEntity<ApiResponse<ShipmentTrackingResponse>> syncShipment(
            @PathVariable long orderId,
            @PathVariable long shipmentId
    ) {
        return noStore(coordinator.syncForAdmin(orderId, shipmentId));
    }

    private ResponseEntity<ApiResponse<ShipmentTrackingResponse>> noStore(
            ShipmentTrackingResponse response
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}
