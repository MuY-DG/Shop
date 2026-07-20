package org.muybaby.shopserver.order.dto;

public record OrderPreviewItemResponse(
        Long cartItemId,
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
        Long retailUnitPriceCent,
        Integer wholesaleTierMinQuantity,
        Integer quantity,
        Long lineOriginalAmountCent,
        Long lineAmountCent
) {
    public OrderPreviewItemResponse(
            Long cartItemId,
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
        this(
                cartItemId, skuId, spuId, productTitle, productSubtitle, mainImage, mainImageFileId,
                skuImage, skuImageFileId, displayImage, displayImageFileId, skuCode, specText,
                originalPriceCent, unitPriceCent, unitPriceCent, null, quantity,
                lineOriginalAmountCent, lineAmountCent
        );
    }
}
