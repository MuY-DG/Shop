package org.muybaby.shopserver.aftersale.dto;

public record AdminAfterSaleStatusCountsResponse(
        long all,
        long pendingReview,
        long refunding,
        long refunded,
        long rejected,
        long refundFailed
) {
}
