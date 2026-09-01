package org.muybaby.shopserver.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
public class AfterSaleChangedRealtimeListener {

    private final RealtimeSessionHub realtimeSessionHub;

    public AfterSaleChangedRealtimeListener(RealtimeSessionHub realtimeSessionHub) {
        this.realtimeSessionHub = realtimeSessionHub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAfterSaleChanged(AfterSaleChangedRealtimeEvent event) {
        realtimeSessionHub.sendToAdminsMatching(
                "AFTER_SALE_CHANGED",
                Map.of(
                        "afterSaleId", event.afterSaleId(),
                        "fromStatus", event.fromStatus(),
                        "toStatus", event.toStatus(),
                        "eventType", event.eventType(),
                        "occurredAt", event.occurredAt()
                ),
                principal -> principal.permissions().contains("aftersale:read")
                        || principal.permissions().contains("order:read")
        );
    }
}
