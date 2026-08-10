package org.muybaby.shopserver.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record LegalDocumentDraftRequest(
        @NotBlank @Size(max = 40) String version,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 100000) String content,
        LocalDateTime effectiveAt
) {
}
