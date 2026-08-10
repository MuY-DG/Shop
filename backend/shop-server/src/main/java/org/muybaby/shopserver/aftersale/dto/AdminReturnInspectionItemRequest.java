package org.muybaby.shopserver.aftersale.dto;

public record AdminReturnInspectionItemRequest(
        Long orderItemId,
        Integer restockQuantity
) {
}
