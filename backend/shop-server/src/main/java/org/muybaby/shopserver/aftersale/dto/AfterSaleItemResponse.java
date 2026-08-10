package org.muybaby.shopserver.aftersale.dto;

public record AfterSaleItemResponse(
        Long id,
        Long orderItemId,
        Long skuId,
        String productTitle,
        String specText,
        String image,
        Integer requestedQuantity,
        Integer approvedQuantity,
        Long requestedAmountCent,
        Long approvedAmountCent,
        Integer restockQuantity
) {
}
