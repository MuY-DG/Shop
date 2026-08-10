package org.muybaby.shopserver.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductFoodLabelAssetRequest(
        @NotNull Long fileId,
        @Size(max = 500) String url,
        @Min(0) Integer sortOrder
) {
}
