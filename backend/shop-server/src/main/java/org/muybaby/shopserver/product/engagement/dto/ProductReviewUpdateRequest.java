package org.muybaby.shopserver.product.engagement.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductReviewUpdateRequest(
        @NotNull @Min(1) @Max(5) Integer rating,
        @Size(max = 1000) String content,
        Boolean anonymous
) {
}
