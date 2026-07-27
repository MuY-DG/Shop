package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StorageAssetBatchDeleteRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> assetIds
) {
}
