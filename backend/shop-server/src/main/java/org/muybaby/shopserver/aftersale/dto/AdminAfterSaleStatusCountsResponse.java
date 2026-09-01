package org.muybaby.shopserver.aftersale.dto;

public record AdminAfterSaleStatusCountsResponse(
        long all,
        long pendingReview,
        long returnProcessing,
        long pendingInspection,
        long refunding,
        long refunded,
        long rejected,
        long refundFailed
) {
}
