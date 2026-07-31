package org.muybaby.shopserver.storage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record DirectUploadSessionRequest(
        @NotBlank @Size(max = 255) String originalFilename,
        @NotBlank @Size(max = 128) String contentType,
        @Positive long sizeBytes,
        @Positive Long folderId
) {
}
