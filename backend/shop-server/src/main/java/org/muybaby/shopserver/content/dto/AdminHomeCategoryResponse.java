package org.muybaby.shopserver.content.dto;

import java.time.LocalDateTime;

public record AdminHomeCategoryResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String categoryStatus,
        Long imageFileId,
        String imageUrl,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
