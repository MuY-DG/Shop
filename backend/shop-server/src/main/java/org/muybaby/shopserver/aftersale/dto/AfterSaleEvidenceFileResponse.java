package org.muybaby.shopserver.aftersale.dto;

public record AfterSaleEvidenceFileResponse(
        Long fileId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String visibility,
        String purpose,
        String status
) {
}
