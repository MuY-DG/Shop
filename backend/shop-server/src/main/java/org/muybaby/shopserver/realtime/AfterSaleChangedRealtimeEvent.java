package org.muybaby.shopserver.realtime;

import java.time.LocalDateTime;

public record AfterSaleChangedRealtimeEvent(
        long afterSaleId,
        String fromStatus,
        String toStatus,
        String eventType,
        LocalDateTime occurredAt
) {
}
