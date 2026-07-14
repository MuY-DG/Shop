package org.muybaby.shopserver.coupon.dto;

import java.util.List;

public record ProductCouponBindingRequest(
        List<Long> couponTemplateIds
) {
}
