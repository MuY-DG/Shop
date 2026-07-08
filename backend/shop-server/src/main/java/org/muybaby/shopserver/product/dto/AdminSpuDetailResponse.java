package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminSpuDetailResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        Long mainImageFileId,
        String sellingPoints,
        String detailHtml,
        Integer sortOrder,
        String status,
        List<ProductImageResponse> images,
        List<AdminSkuResponse> skus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
