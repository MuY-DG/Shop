package org.muybaby.shopserver.product.engagement.dto;

import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.product.engagement.ProductReviewFilter;
import org.muybaby.shopserver.product.engagement.ProductReviewSort;

public record ProductReviewPageRequest(
        Long current,
        Long size,
        ProductReviewFilter filter,
        ProductReviewSort sort,
        @Size(max = 500) String specText
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 10 : Math.min(size, 50);
    }

    public ProductReviewFilter pageFilter() {
        return filter == null ? ProductReviewFilter.ALL : filter;
    }

    public ProductReviewSort pageSort() {
        return sort == null ? ProductReviewSort.RECOMMENDED : sort;
    }

    public String normalizedSpecText() {
        return specText == null ? "" : specText.trim();
    }
}
