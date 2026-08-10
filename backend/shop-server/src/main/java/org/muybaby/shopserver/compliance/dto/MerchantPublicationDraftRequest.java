package org.muybaby.shopserver.compliance.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record MerchantPublicationDraftRequest(
        @Size(max = 160) String legalName,
        @Size(max = 32) String entityType,
        @Size(max = 32) String unifiedSocialCreditCode,
        @Size(max = 512) String businessAddress,
        @Size(max = 32) String customerServicePhone,
        @Size(max = 32) String complaintPhone,
        Long businessLicenseAssetId,
        @Size(max = 40) String foodQualificationType,
        @Size(max = 96) String foodQualificationNumber,
        Long foodQualificationAssetId,
        LocalDate foodQualificationValidFrom,
        LocalDate foodQualificationValidUntil
) {
}
