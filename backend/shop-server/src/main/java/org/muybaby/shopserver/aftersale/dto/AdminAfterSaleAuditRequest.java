package org.muybaby.shopserver.aftersale.dto;

public record AdminAfterSaleAuditRequest(
        Long approvedAmountCent,
        String auditNote
) {
}
