package org.muybaby.shopserver.storage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record StorageAssetUsageResponse(
        Long id,
        Long assetId,
        String usageType,
        String ownerType,
        Long ownerId,
        String ownerLabel,
        String snapshotUrl,
        Integer sortOrder,
        @JsonProperty("protected") boolean protectedUsage,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
