package org.muybaby.shopserver.product.engagement.dto;

import org.muybaby.shopserver.product.engagement.ProductReviewStatus;

import java.time.LocalDateTime;

public record AdminProductReviewResponse(
        Long id,
        Long spuId,
        String productTitle,
        String productImage,
        Long userId,
        String reviewerName,
        Long orderId,
        String orderNo,
        Long orderItemId,
        boolean orderDataCleaned,
        String specText,
        boolean verifiedPurchase,
        Integer rating,
        String content,
        Boolean anonymous,
        ProductReviewStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long moderatedByAdminUserId,
        LocalDateTime moderatedAt
) {
}
