package org.muybaby.shopserver.product.engagement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.product.engagement.ProductReviewStatus;

public record AdminProductReviewQueryRequest(
        Long current,
        Long size,
        Long spuId,
        @Size(max = 100) String productTitle,
        @Min(1) @Max(5) Integer rating,
        ProductReviewStatus status,
        Boolean anonymous
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 100);
    }

    public String normalizedProductTitle() {
        return productTitle == null ? "" : productTitle.trim();
    }
}
