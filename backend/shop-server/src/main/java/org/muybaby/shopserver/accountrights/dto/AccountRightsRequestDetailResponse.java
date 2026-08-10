package org.muybaby.shopserver.accountrights.dto;

import java.util.List;

public record AccountRightsRequestDetailResponse(
        AccountRightsRequestResponse request,
        List<AccountRightsAuditResponse> audits
) {
    public AccountRightsRequestDetailResponse {
        audits = audits == null ? List.of() : List.copyOf(audits);
    }
}
