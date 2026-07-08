package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotNull;

public record StorageFileMoveRequest(@NotNull Long assetCategoryId) {
}
