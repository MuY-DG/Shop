package org.muybaby.shopserver.realtime;

import java.time.LocalDateTime;

public record OrderPaidRealtimeEvent(
        Long orderId,
        String orderNo,
        long paidAmountCent,
        LocalDateTime paidAt
) {
}
