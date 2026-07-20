package org.muybaby.shopserver.aftersale.dto;

public record AdminRefundOperationResponse(
        String action,
        String result,
        String providerStatus,
        boolean resubmitted,
        AfterSaleResponse afterSale
) {
}
