package org.muybaby.shopserver.product.dto;

import java.math.BigDecimal;

public record AppProductReviewSummaryResponse(
        long reviewCount,
        BigDecimal averageRating,
        long goodReviewCount
) {
}
