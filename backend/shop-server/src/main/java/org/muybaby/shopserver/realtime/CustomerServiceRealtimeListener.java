package org.muybaby.shopserver.realtime;

import org.muybaby.shopserver.customerservice.CustomerServiceChangedEvent;
import org.muybaby.shopserver.customerservice.CustomerServiceTransferChangedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CustomerServiceRealtimeListener {

    private final RealtimeSessionHub realtimeSessionHub;
    private final JdbcClient jdbcClient;

    public CustomerServiceRealtimeListener(
            RealtimeSessionHub realtimeSessionHub,
            JdbcClient jdbcClient
    ) {
        this.realtimeSessionHub = realtimeSessionHub;
        this.jdbcClient = jdbcClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCustomerServiceChanged(CustomerServiceChangedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", event.conversationId());
        data.put("appUserId", event.appUserId().toString());
        data.put("changeType", event.changeType());
        data.put("messageId", event.messageId());
        if (event.messageId() != null) {
            jdbcClient.sql("""
                            select message.sender_type,
                                   case
                                     when message.sender_type = 'APP_USER'
                                       then coalesce(app.nickname, '用户')
                                     else null
                                   end as sender_name,
                                   message.message_type,
                                   message.content
                            from customer_service_message message
                            left join app_user app
                              on message.sender_type = 'APP_USER' and app.id = message.sender_id
                            where message.id = :messageId
                              and message.conversation_id = :conversationId
                            """)
                    .param("messageId", event.messageId())
                    .param("conversationId", event.conversationId())
                    .query((rs, rowNum) -> new RealtimeMessage(
                            rs.getString("sender_type"),
                            rs.getString("sender_name"),
                            rs.getString("message_type"),
                            rs.getString("content")
                    ))
                    .optional()
                    .ifPresent(message -> {
                        data.put("senderType", message.senderType());
                        data.put("senderName", message.senderName());
                        data.put("messageType", message.messageType());
                        data.put("messageContent", message.content());
                    });
        }
        realtimeSessionHub.sendToAppUser(
                event.appUserId(), "CUSTOMER_SERVICE_CONVERSATION_UPDATED", data
        );
        ConversationAudience audience = jdbcClient.sql("""
                        select status, assigned_admin_user_id
                        from customer_service_conversation
                        where id = :conversationId
                        """)
                .param("conversationId", event.conversationId())
                .query((rs, rowNum) -> new ConversationAudience(
                        rs.getString("status"),
                        rs.getObject("assigned_admin_user_id", Long.class)
                ))
                .optional()
                .orElse(null);
        if (audience == null) {
            return;
        }
        realtimeSessionHub.sendToAdminsMatching(
                "CUSTOMER_SERVICE_CONVERSATION_UPDATED",
                data,
                principal -> principal.permissions().contains("customer-service:conversation:read")
                        && (
                            "WAITING".equals(audience.status())
                            || principal.subjectId().equals(audience.assignedAdminUserId())
                            || principal.permissions().contains("customer-service:agent:manage")
                        )
        );
        if ("CONVERSATION_CLAIMED".equals(event.changeType())
                || "CONVERSATION_AUTO_ASSIGNED".equals(event.changeType())) {
            realtimeSessionHub.sendToAdminsWithPermission(
                    "customer-service:conversation:read",
                    "CUSTOMER_SERVICE_QUEUE_UPDATED",
                    Map.of("conversationId", event.conversationId())
            );
        }
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

    private record ConversationAudience(String status, Long assignedAdminUserId) {
    }

    private record RealtimeMessage(
            String senderType,
            String senderName,
            String messageType,
            String content
    ) {
    }
}
