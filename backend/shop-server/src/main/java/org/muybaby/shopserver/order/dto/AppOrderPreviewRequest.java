package org.muybaby.shopserver.order.dto;

import java.util.List;

public record AppOrderPreviewRequest(
        List<Long> cartItemIds,
        Long userCouponId
) {
}
