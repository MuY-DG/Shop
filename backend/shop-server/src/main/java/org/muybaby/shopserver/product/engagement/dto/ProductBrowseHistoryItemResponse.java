package org.muybaby.shopserver.product.engagement.dto;

import java.time.LocalDateTime;

public record ProductBrowseHistoryItemResponse(
        Long spuId,
        String title,
        String subtitle,
        String mainImage,
        Long minPriceCent,
        Long maxPriceCent,
        boolean available,
        LocalDateTime firstViewedAt,
        LocalDateTime lastViewedAt,
        long viewCount
) {
}
