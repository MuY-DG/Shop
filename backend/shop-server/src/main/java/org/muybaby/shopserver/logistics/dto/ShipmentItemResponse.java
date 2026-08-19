package org.muybaby.shopserver.logistics.dto;

public record ShipmentItemResponse(
        Long orderItemId,
        String productTitle,
        String specText,
        Integer quantity
) {
}
