package org.muybaby.shopserver.aftersale.dto;

import java.time.Instant;

public record AfterSaleEvidenceFileResponse(
        Long fileId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String scope,
        String mediaKind,
        String visibility,
        String status,
        String accessMode,
        String accessUrl,
        Instant accessExpiresAt
) {
}
