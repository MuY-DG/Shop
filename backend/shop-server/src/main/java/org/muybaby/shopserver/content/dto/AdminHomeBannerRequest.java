package org.muybaby.shopserver.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record AdminHomeBannerRequest(
        @NotBlank String title,
        String subtitle,
        @NotNull Long imageFileId,
        @NotBlank String jumpType,
        Long jumpTargetId,
        String jumpPath,
        @NotBlank String status,
        Integer sortOrder,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}
