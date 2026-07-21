package org.muybaby.shopserver.product.engagement.dto;

import java.time.LocalDateTime;

public record ProductReviewResponse(
        Long id,
        Long spuId,
        String productTitle,
        Long orderItemId,
        String skuSpecText,
        Integer rating,
        String content,
        boolean anonymous,
        String reviewerName,
        boolean verifiedPurchase,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
