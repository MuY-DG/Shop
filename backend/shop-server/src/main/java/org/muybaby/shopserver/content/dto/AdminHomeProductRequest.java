package org.muybaby.shopserver.content.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminHomeProductRequest(
        @NotNull Long spuId,
        Long imageFileId,
        @Min(0) Integer sortOrder,
        @NotBlank String status,
        String badgeMode,
        String customBadgeText
) {
    public AdminHomeProductRequest(Long spuId, Long imageFileId, Integer sortOrder, String status) {
        this(spuId, imageFileId, sortOrder, status, "AUTO", "");
    }
}
