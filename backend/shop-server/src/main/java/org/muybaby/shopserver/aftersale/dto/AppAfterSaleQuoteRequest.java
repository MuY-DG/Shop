package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AppAfterSaleQuoteRequest(
        String afterSaleType,
        List<AfterSaleItemRequest> items
) {
}
