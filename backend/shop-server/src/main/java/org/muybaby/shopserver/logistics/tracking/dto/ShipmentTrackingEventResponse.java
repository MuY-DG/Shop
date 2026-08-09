package org.muybaby.shopserver.logistics.tracking.dto;

public record ShipmentTrackingEventResponse(
        long actionTime,
        int actionType,
        String actionMessage
) {
}
