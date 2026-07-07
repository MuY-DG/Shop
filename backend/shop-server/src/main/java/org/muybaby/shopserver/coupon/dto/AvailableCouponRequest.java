package org.muybaby.shopserver.coupon.dto;

import java.util.List;

public record AvailableCouponRequest(List<Long> cartItemIds) {
}
