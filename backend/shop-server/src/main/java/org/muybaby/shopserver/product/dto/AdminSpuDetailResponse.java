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
        String mainVideo,
        Long mainVideoFileId,
        String specType,
        Long freightTemplateId,
        Long virtualSales,
        String sellingPoints,
        String detailHtml,
        String displayBadgeText,
        String displayBadgeTone,
        Integer sortOrder,
        String status,
        List<ProductImageResponse> images,
        List<AdminSkuResponse> skus,
        List<AdminSpuSpecGroupResponse> specGroups,
        List<Long> guaranteeServiceIds,
        List<Long> couponTemplateIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
