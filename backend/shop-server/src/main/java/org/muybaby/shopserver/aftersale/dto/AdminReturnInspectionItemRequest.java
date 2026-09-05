package org.muybaby.shopserver.aftersale.dto;

public record AdminReturnInspectionItemRequest(
        Long orderItemId,
        Integer restockQuantity,
        Integer receivedQuantity
) {
    public AdminReturnInspectionItemRequest(Long orderItemId, Integer restockQuantity) {
        this(orderItemId, restockQuantity, null);
    }
}
