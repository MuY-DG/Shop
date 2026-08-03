package org.muybaby.shopserver.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.customerservice.CustomerServiceAgentAvailableEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Locale;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RealtimeSessionHub realtimeSessionHub;
    private final ApplicationEventPublisher eventPublisher;

    public RealtimeWebSocketHandler(
            ObjectMapper objectMapper,
            RealtimeSessionHub realtimeSessionHub,
            ApplicationEventPublisher eventPublisher
    ) {
        this.objectMapper = objectMapper;
        this.realtimeSessionHub = realtimeSessionHub;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object value = session.getAttributes().get(RealtimeHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof RealtimeConnectionPrincipal principal)) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Missing realtime principal"));
            return;
        }
        realtimeSessionHub.register(session, principal);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        switch (messageType(message.getPayload())) {
            case "CUSTOMER_SERVICE_PRESENCE_START" -> {
                Long availableAdminUserId =
                        realtimeSessionHub.startCustomerServicePresence(session);
                if (realtimeSessionHub.isCustomerServicePresenceActive(session)) {
                    realtimeSessionHub.sendCustomerServicePresenceStarted(session);
                }
                publishAvailable(availableAdminUserId);
            }
            case "CUSTOMER_SERVICE_PRESENCE_STOP" ->
                    realtimeSessionHub.stopCustomerServicePresence(session);
            case "PING" -> {
                publishAvailable(realtimeSessionHub.touchCustomerServicePresence(session));
                realtimeSessionHub.sendPong(session);
            }
            default -> {
                // Unknown transport control messages are intentionally ignored.
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        realtimeSessionHub.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        realtimeSessionHub.unregister(session);
    }

    private void publishAvailable(Long adminUserId) {
        if (adminUserId != null) {
            eventPublisher.publishEvent(new CustomerServiceAgentAvailableEvent(adminUserId));
        }
    }

    private String messageType(String payload) {
        String normalized = payload == null ? "" : payload.trim();
        if ("PING".equalsIgnoreCase(normalized)) {
            return "PING";
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }
}
