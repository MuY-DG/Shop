package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StorageAssetDisplayNameRequest(
        @NotBlank @Size(max = 255) String displayName
) {
}
