package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StorageAssetCategoryRequest(
        @NotNull Long parentId,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 64) String code,
        @Size(max = 255) String description,
        Integer sortOrder,
        @NotBlank @Size(max = 20) String status
) {
}
