package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;

public record AdminSpuListItemResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String subtitle,
        String mainImage,
        String status,
        Integer sortOrder,
        Long minPriceCent,
        Long maxPriceCent,
        Integer totalStock,
        Integer skuCount,
        Long actualSales,
        Long virtualSales,
        Long displaySales,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
