package org.muybaby.shopserver.coupon.dto;

import java.util.List;

public record AvailableCouponResponse(
        Long cartAmountCent,
        Long bestUserCouponId,
        Long bestDiscountCent,
        Long payableAmountCent,
        List<AvailableCouponItemResponse> coupons
) {
}
