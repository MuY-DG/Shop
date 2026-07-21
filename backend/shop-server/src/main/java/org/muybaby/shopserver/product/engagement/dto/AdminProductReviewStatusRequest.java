package org.muybaby.shopserver.product.engagement.dto;

import jakarta.validation.constraints.NotNull;
import org.muybaby.shopserver.product.engagement.ProductReviewStatus;

public record AdminProductReviewStatusRequest(
        @NotNull ProductReviewStatus status
) {
}
