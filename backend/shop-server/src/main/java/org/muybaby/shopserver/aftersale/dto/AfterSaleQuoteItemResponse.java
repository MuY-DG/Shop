package org.muybaby.shopserver.aftersale.dto;

public record AfterSaleQuoteItemResponse(
        Long orderItemId,
        Integer quantity,
        Long requestedAmountCent
) {
}
