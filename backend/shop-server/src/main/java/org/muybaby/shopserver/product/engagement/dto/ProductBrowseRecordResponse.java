package org.muybaby.shopserver.product.engagement.dto;

import java.time.LocalDateTime;

public record ProductBrowseRecordResponse(
        Long spuId,
        LocalDateTime lastViewedAt,
        long viewCount
) {
}
