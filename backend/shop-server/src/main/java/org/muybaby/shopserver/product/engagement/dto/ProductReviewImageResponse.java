package org.muybaby.shopserver.product.engagement.dto;

public record ProductReviewImageResponse(
        Long fileId,
        String url,
        Integer sortOrder
) {
}
