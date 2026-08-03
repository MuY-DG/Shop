package org.muybaby.shopserver.order.dto;

public record OrderSummaryItemResponse(
        Long orderItemId,
        Long skuId,
        Long spuId,
        String productTitle,
        String productSubtitle,
        String mainImage,
        String skuImage,
        String displayImage,
        String skuCode,
        String specText,
        Long unitPriceCent,
        Integer quantity,
        boolean reviewed,
        boolean reviewable
) {
}
