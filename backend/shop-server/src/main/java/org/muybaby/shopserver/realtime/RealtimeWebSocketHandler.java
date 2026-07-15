package org.muybaby.shopserver.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RealtimeSessionHub realtimeSessionHub;

    public RealtimeWebSocketHandler(ObjectMapper objectMapper, RealtimeSessionHub realtimeSessionHub) {
        this.objectMapper = objectMapper;
        this.realtimeSessionHub = realtimeSessionHub;
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
        if (isPing(message.getPayload())) {
            realtimeSessionHub.sendPong(session);
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

    private boolean isPing(String payload) {
        if ("PING".equalsIgnoreCase(payload == null ? "" : payload.trim())) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return "PING".equalsIgnoreCase(node.path("type").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
