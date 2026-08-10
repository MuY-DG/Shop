package org.muybaby.shopserver.aftersale.dto;

public record AfterSaleItemRequest(
        Long orderItemId,
        Integer quantity
) {
}
