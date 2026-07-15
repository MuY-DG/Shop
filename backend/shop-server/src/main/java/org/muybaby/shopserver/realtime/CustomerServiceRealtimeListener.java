package org.muybaby.shopserver.realtime;

import org.muybaby.shopserver.customerservice.CustomerServiceChangedEvent;
import org.muybaby.shopserver.customerservice.CustomerServiceTransferChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CustomerServiceRealtimeListener {

    private final RealtimeSessionHub realtimeSessionHub;

    public CustomerServiceRealtimeListener(RealtimeSessionHub realtimeSessionHub) {
        this.realtimeSessionHub = realtimeSessionHub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCustomerServiceChanged(CustomerServiceChangedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", event.conversationId());
        data.put("appUserId", event.appUserId().toString());
        data.put("changeType", event.changeType());
        data.put("messageId", event.messageId());
        realtimeSessionHub.sendToAppUser(
                event.appUserId(), "CUSTOMER_SERVICE_CONVERSATION_UPDATED", data
        );
        realtimeSessionHub.sendToAdminsWithPermission(
                "customer-service:conversation:read",
                "CUSTOMER_SERVICE_CONVERSATION_UPDATED",
                data
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCustomerServiceTransferChanged(CustomerServiceTransferChangedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", event.requestId());
        data.put("conversationId", event.conversationId());
        data.put("fromAdminUserId", event.fromAdminUserId());
        data.put("toAdminUserId", event.toAdminUserId());
        data.put("changeType", event.changeType());
        String type = "CUSTOMER_SERVICE_TRANSFER_" + event.changeType();
        realtimeSessionHub.sendToAdminUser(event.fromAdminUserId(), type, data);
        if (!event.fromAdminUserId().equals(event.toAdminUserId())) {
            realtimeSessionHub.sendToAdminUser(event.toAdminUserId(), type, data);
        }
    }
}
