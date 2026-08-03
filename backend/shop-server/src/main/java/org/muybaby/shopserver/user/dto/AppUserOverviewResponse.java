package org.muybaby.shopserver.user.dto;

public record AppUserOverviewResponse(
        long availableCouponCount,
        long favoriteCount,
        long browseHistoryCount,
        long unpaidOrderCount,
        long toShipOrderCount,
        long toReceiveOrderCount,
        long toReviewOrderCount,
        long activeAfterSaleCount,
        long customerServiceUnreadCount,
        boolean customerServiceOnline
) {
}
