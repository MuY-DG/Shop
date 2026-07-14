package org.muybaby.shopserver.storage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StorageAssetResponse(
        Long id,
        String scope,
        String mediaKind,
        Long folderId,
        String visibility,
        String provider,
        String originalFilename,
        String contentType,
        String extension,
        Long sizeBytes,
        String sha256,
        Integer width,
        Integer height,
        Integer durationSeconds,
        String altText,
        List<String> tags,
        String status,
        String uploadedByType,
        @JsonStringId Long uploadedById,
        String url,
        String publicUrl,
        Long usageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime deletedAt,
        List<StorageAssetUsageResponse> usages
) {
}
