package org.muybaby.shopserver.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
public class OrderPaidRealtimeListener {

    private final RealtimeSessionHub realtimeSessionHub;

    public OrderPaidRealtimeListener(RealtimeSessionHub realtimeSessionHub) {
        this.realtimeSessionHub = realtimeSessionHub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPaid(OrderPaidRealtimeEvent event) {
        realtimeSessionHub.sendToAdminsWithPermission(
                "order:read",
                "ORDER_PAID",
                Map.of(
                        "orderId", event.orderId(),
                        "orderNo", event.orderNo(),
                        "paidAmountCent", event.paidAmountCent(),
                        "paidAt", event.paidAt()
                )
        );
    }
}
