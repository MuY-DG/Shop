package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AfterSaleQuoteResponse(
        Long orderId,
        String afterSaleType,
        Long requestedAmountCent,
        String quoteDigest,
        List<AfterSaleQuoteItemResponse> items
) {
}
