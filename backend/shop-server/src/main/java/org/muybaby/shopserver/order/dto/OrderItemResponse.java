package org.muybaby.shopserver.order.dto;

public record OrderItemResponse(
        Long orderItemId,
        Long skuId,
        Long spuId,
        String productTitle,
        String productSubtitle,
        String mainImage,
        Long mainImageFileId,
        String skuImage,
        Long skuImageFileId,
        String displayImage,
        Long displayImageFileId,
        String skuCode,
        String specText,
        Long originalPriceCent,
        Long unitPriceCent,
        Integer quantity,
        Long lineOriginalAmountCent,
        Long lineAmountCent
) {
}
