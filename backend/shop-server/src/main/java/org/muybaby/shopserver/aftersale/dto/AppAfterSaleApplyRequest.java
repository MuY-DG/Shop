package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AppAfterSaleApplyRequest(
        String requestKey,
        String quoteDigest,
        String afterSaleType,
        String reason,
        Long requestedAmountCent,
        String description,
        List<Long> evidenceFileIds,
        List<AfterSaleItemRequest> items
) {
    public AppAfterSaleApplyRequest(
            String afterSaleType,
            String reason,
            Long requestedAmountCent,
            String description,
            List<Long> evidenceFileIds
    ) {
        this(null, null, afterSaleType, reason, requestedAmountCent, description, evidenceFileIds, null);
    }
}
