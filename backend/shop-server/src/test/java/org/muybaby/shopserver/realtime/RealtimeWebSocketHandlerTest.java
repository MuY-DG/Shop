package org.muybaby.shopserver.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.customerservice.CustomerServiceAgentAvailableEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void customerServicePresenceUsesTheExistingConnectionAndHeartbeatLease() throws Exception {
        RealtimeSessionHub hub = new RealtimeSessionHub(objectMapper);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
                objectMapper, hub, eventPublisher);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("customer-service-workspace");
        when(session.isOpen()).thenReturn(true);
        hub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                7L,
                "agent-7",
                List.of("customer-service:conversation:read")
        ));

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"CUSTOMER_SERVICE_PRESENCE_START\"}"));

        assertThat(hub.isCustomerServiceAgentOnline(7L)).isTrue();
        assertThat(messageType(session)).isEqualTo("CUSTOMER_SERVICE_PRESENCE_STARTED");
        verify(eventPublisher).publishEvent(new CustomerServiceAgentAvailableEvent(7L));

        clearInvocations(session);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"PING\"}"));
        assertThat(messageType(session)).isEqualTo("PONG");
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isTrue();

        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"CUSTOMER_SERVICE_PRESENCE_STOP\"}"));
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isFalse();
    }

    private String messageType(WebSocketSession session) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        return objectMapper.readTree(((TextMessage) captor.getValue()).getPayload())
                .path("type")
                .asText();
    }
}
