package org.muybaby.shopserver.compliance.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record LegalDocumentResponse(
        @JsonStringId Long id,
        String documentType,
        String version,
        String title,
        String content,
        String contentSha256,
        String status,
        LocalDateTime effectiveAt,
        @JsonStringId Long createdBy,
        @JsonStringId Long publishedBy,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
