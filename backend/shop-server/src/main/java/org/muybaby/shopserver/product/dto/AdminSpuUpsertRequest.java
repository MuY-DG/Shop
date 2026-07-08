package org.muybaby.shopserver.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AdminSpuUpsertRequest(
        @NotNull Long categoryId,
        @NotBlank String title,
        String subtitle,
        @NotBlank String mainImage,
        Long mainImageFileId,
        String sellingPoints,
        String detailHtml,
        @NotNull @Min(0) Integer sortOrder,
        @Valid List<AdminProductImageUpsertRequest> images,
        @Valid List<AdminSkuUpsertRequest> skus
) {
}
