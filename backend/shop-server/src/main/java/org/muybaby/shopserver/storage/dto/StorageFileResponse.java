package org.muybaby.shopserver.storage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StorageFileResponse(
        Long id,
        String purpose,
        Long assetCategoryId,
        String visibility,
        String provider,
        String originalFilename,
        String contentType,
        String extension,
        Long sizeBytes,
        String sha256,
        Integer width,
        Integer height,
        String status,
        String uploadedByType,
        Long uploadedById,
        String url,
        String publicUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt,
        List<StorageFileUsageResponse> usages
) {
}
