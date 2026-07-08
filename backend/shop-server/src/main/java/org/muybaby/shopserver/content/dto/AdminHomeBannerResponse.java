package org.muybaby.shopserver.content.dto;

import java.time.LocalDateTime;

public record AdminHomeBannerResponse(
        Long id,
        String title,
        String subtitle,
        Long imageFileId,
        String imageUrl,
        String jumpType,
        Long jumpTargetId,
        String jumpPath,
        String status,
        Integer sortOrder,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
