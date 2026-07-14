package org.muybaby.shopserver.product.dto;

import java.time.LocalDateTime;

public record AdminSpecTemplateSummaryResponse(
        Long id,
        String name,
        Integer groupCount,
        Integer valueCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
