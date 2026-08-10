package org.muybaby.shopserver.aftersale.dto;

import java.util.List;

public record AfterSaleEligibilityResponse(
        Long orderId,
        String orderNo,
        String orderStatus,
        Long activeAfterSaleId,
        Long paidAmountCent,
        Long refundedAmountCent,
        Long remainingRefundableAmountCent,
        List<String> availableTypes,
        List<AfterSaleEligibilityItemResponse> items
) {
}
