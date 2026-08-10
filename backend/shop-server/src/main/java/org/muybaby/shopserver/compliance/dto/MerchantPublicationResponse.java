package org.muybaby.shopserver.compliance.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MerchantPublicationResponse(
        @JsonStringId Long id,
        Long revisionNo,
        String status,
        String legalName,
        String entityType,
        String unifiedSocialCreditCode,
        String businessAddress,
        String customerServicePhone,
        String complaintPhone,
        Long businessLicenseAssetId,
        String businessLicenseUrl,
        String foodQualificationType,
        String foodQualificationNumber,
        Long foodQualificationAssetId,
        String foodQualificationUrl,
        LocalDate foodQualificationValidFrom,
        LocalDate foodQualificationValidUntil,
        @JsonStringId Long createdBy,
        @JsonStringId Long publishedBy,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
