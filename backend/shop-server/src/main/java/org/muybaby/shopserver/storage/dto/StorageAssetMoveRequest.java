package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotNull;

public record StorageAssetMoveRequest(@NotNull Long folderId) {
}
