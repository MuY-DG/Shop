package org.muybaby.shopserver.product.engagement.dto;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AppProductReviewSummaryResponse;

public record ProductReviewPageResponse(
        AppProductReviewSummaryResponse summary,
        PageResult<PublicProductReviewResponse> page
) {
}
