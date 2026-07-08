package org.muybaby.shopserver.storage.dto;

import java.time.LocalDateTime;
import java.util.List;

public record StorageAssetCategoryResponse(
        Long id,
        Long parentId,
        String name,
        String code,
        String description,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<StorageAssetCategoryResponse> children
) {
}
