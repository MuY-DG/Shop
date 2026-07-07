package org.muybaby.shopserver.promotion;

public record CheckoutItem(
        Long skuId,
        Long spuId,
        Long lineAmountCent,
        Integer quantity
) {
}
