package org.muybaby.shopserver.product.engagement.dto;

import java.time.LocalDateTime;

public record PublicProductReviewResponse(
        Long id,
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
