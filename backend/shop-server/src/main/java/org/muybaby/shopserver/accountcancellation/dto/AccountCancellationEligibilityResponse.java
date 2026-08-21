package org.muybaby.shopserver.accountcancellation.dto;

public record AccountCancellationEligibilityResponse(
        boolean eligible,
        long activeOrderCount,
        long activePaymentCount,
        long activeRefundCount,
        long activeAfterSaleCount
) {
}
