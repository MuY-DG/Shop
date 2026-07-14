package org.muybaby.shopserver.storage.dto;

import java.time.LocalDateTime;
import java.util.List;

public record StorageAssetFolderResponse(
        Long id,
        Long parentId,
        String name,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<StorageAssetFolderResponse> children
) {
}
