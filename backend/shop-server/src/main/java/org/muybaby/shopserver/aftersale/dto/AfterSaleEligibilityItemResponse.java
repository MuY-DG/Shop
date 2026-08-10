package org.muybaby.shopserver.aftersale.dto;

public record AfterSaleEligibilityItemResponse(
        Long orderItemId,
        Long skuId,
        String productTitle,
        String specText,
        String image,
        Integer purchasedQuantity,
        Integer refundedQuantity,
        Integer availableQuantity,
        Long paidAmountBasisCent
) {
}
