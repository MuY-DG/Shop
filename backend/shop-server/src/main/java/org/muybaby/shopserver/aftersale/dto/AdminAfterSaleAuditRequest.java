package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AdminAfterSaleAuditRequest(
        Long approvedAmountCent,
        String auditNote,
        Long returnAddressId,
        List<AdminAfterSaleItemApprovalRequest> items
) {
    public AdminAfterSaleAuditRequest(Long approvedAmountCent, String auditNote) {
        this(approvedAmountCent, auditNote, null, null);
    }
}
