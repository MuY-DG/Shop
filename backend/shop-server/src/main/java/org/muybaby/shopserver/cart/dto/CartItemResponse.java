package org.muybaby.shopserver.cart.dto;

import java.time.LocalDateTime;

public record CartItemResponse(
        Long id,
        Long skuId,
        Long spuId,
        String productTitle,
        String productSubtitle,
        String mainImage,
        String skuImage,
        String displayImage,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Integer quantity,
        Long lineAmountCent,
        Integer stockAvailable,
        String skuStatus,
        String spuStatus,
        Boolean available,
        String unavailableReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
