package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StorageAssetFolderPositionRequest(
        @NotNull Long parentId,
        @NotNull @Min(0) Integer index
) {
}
