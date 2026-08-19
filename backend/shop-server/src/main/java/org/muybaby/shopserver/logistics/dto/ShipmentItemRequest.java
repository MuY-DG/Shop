package org.muybaby.shopserver.logistics.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ShipmentItemRequest(
        @NotNull Long orderItemId,
        @NotNull @Min(1) Integer quantity
) {
}
