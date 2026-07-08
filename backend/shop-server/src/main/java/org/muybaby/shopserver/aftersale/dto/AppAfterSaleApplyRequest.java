package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AppAfterSaleApplyRequest(
        String afterSaleType,
        String reason,
        Long requestedAmountCent,
        String description,
        List<Long> evidenceFileIds
) {
}
