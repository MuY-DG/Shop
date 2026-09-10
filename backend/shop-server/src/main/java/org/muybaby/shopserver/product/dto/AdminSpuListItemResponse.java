package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;
import java.util.List;

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
        LocalDateTime deletedAt,
        List<ProductSearchMatchResponse> searchMatches
) {
    public AdminSpuListItemResponse withSearchMatches(List<ProductSearchMatchResponse> matches) {
        return new AdminSpuListItemResponse(id, categoryId, categoryName, title, subtitle, mainImage,
                status, sortOrder, minPriceCent, maxPriceCent, totalStock, skuCount, actualSales,
                virtualSales, displaySales, createdAt, updatedAt, deletedAt, matches);
    }
}
