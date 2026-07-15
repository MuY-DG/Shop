package org.muybaby.shopserver.realtime;

import java.time.Instant;

public record RealtimeEnvelope(
        String eventId,
        String type,
        Instant occurredAt,
        Object data
) {
}
