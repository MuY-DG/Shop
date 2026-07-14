package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StorageAssetFolderRequest(
        @NotNull Long parentId,
        @NotBlank @Size(max = 64) String name,
        Integer sortOrder,
        @NotBlank @Size(max = 20) String status
) {
}
