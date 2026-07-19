package org.muybaby.shopserver.content.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AdminHomeProductResponse(
        Long id,
        String sectionType,
        Long spuId,
        String productTitle,
        String productSubtitle,
        String productStatus,
        String categoryName,
        Long imageFileId,
        String imageUrl,
        String productImageUrl,
        String displayImageUrl,
        Long minPriceCent,
        Long maxPriceCent,
        Integer sortOrder,
        String status,
        List<String> productTags,
        String badgeMode,
        String customBadgeText,
        String resolvedBadgeText,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
