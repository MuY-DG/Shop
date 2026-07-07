package org.muybaby.shopserver.order.dto;

public record OrderPreviewItemResponse(
        Long cartItemId,
        Long skuId,
        Long spuId,
        String productTitle,
        String productSubtitle,
        String mainImage,
        String skuImage,
        String displayImage,
        String skuCode,
        String specText,
        Long originalPriceCent,
        Long unitPriceCent,
        Integer quantity,
        Long lineOriginalAmountCent,
        Long lineAmountCent
) {
}
