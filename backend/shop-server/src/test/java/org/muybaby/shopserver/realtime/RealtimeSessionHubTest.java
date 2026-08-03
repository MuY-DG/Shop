package org.muybaby.shopserver.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSessionHubTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void routesAppAndAdminEventsOnlyToTheirAuthorizedAudience() throws Exception {
        RealtimeSessionHub hub = new RealtimeSessionHub(objectMapper);
        WebSocketSession app = session("app");
        WebSocketSession otherApp = session("other-app");
        WebSocketSession reader = session("reader");
        WebSocketSession nonReader = session("non-reader");

        hub.register(app, principal(TokenKind.APP, 42L, List.of()));
        hub.register(otherApp, principal(TokenKind.APP, 43L, List.of()));
        hub.register(reader, principal(TokenKind.ADMIN, 7L, List.of("order:read")));
        hub.register(nonReader, principal(TokenKind.ADMIN, 8L, List.of("system:menu:read")));

        hub.sendToAppUser(42L, "CUSTOMER_SERVICE_CONVERSATION_UPDATED", Map.of("conversationId", 9L));

        TextMessage appMessage = captureMessage(app);
        JsonNode appEnvelope = objectMapper.readTree(appMessage.getPayload());
        assertThat(appEnvelope.path("type").asText()).isEqualTo("CUSTOMER_SERVICE_CONVERSATION_UPDATED");
        assertThat(appEnvelope.path("data").path("conversationId").asLong()).isEqualTo(9L);
        verify(otherApp, never()).sendMessage(any());
        verify(reader, never()).sendMessage(any());
        verify(nonReader, never()).sendMessage(any());

        clearInvocations(app, otherApp, reader, nonReader);
        hub.sendToAdminsWithPermission("order:read", "ORDER_PAID", Map.of("orderNo", "ORDER-1"));

        TextMessage adminMessage = captureMessage(reader);
        JsonNode adminEnvelope = objectMapper.readTree(adminMessage.getPayload());
        assertThat(adminEnvelope.path("type").asText()).isEqualTo("ORDER_PAID");
        assertThat(adminEnvelope.path("data").path("orderNo").asText()).isEqualTo("ORDER-1");
        verify(app, never()).sendMessage(any());
        verify(otherApp, never()).sendMessage(any());
        verify(nonReader, never()).sendMessage(any());
    }

    @Test
    void tracksAdminPresenceAcrossMultipleConnectionsAndCanTargetOneAdmin() throws Exception {
        RealtimeSessionHub hub = new RealtimeSessionHub(objectMapper);
        WebSocketSession first = session("agent-first");
        WebSocketSession second = session("agent-second");
        WebSocketSession other = session("other-agent");

        assertThat(hub.isAdminOnline(7L)).isFalse();
        hub.register(first, principal(TokenKind.ADMIN, 7L, List.of("customer-service:conversation:read")));
        hub.register(second, principal(TokenKind.ADMIN, 7L, List.of("customer-service:conversation:read")));
        hub.register(other, principal(TokenKind.ADMIN, 8L, List.of("customer-service:conversation:read")));

        assertThat(hub.isAdminOnline(7L)).isTrue();
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isFalse();
        assertThat(hub.startCustomerServicePresence(first)).isEqualTo(7L);
        assertThat(hub.startCustomerServicePresence(second)).isNull();
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isTrue();
        hub.sendToAdminUser(7L, "CUSTOMER_SERVICE_TRANSFER_REQUESTED", Map.of("requestId", 99L));

        assertThat(objectMapper.readTree(captureMessage(first).getPayload())
                .path("data").path("requestId").asLong()).isEqualTo(99L);
        assertThat(objectMapper.readTree(captureMessage(second).getPayload())
                .path("type").asText()).isEqualTo("CUSTOMER_SERVICE_TRANSFER_REQUESTED");
        verify(other, never()).sendMessage(any());

        hub.unregister(first);
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isTrue();
        hub.stopCustomerServicePresence(second);
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isFalse();
        assertThat(hub.startCustomerServicePresence(second)).isEqualTo(7L);
        assertThat(hub.isCustomerServiceAgentOnlineAt(
                7L,
                Instant.now().plus(RealtimeSessionHub.CUSTOMER_SERVICE_PRESENCE_TTL)
                        .plusSeconds(1)
        )).isFalse();
        assertThat(hub.touchCustomerServicePresence(second)).isNull();
        assertThat(hub.isAdminOnline(7L)).isTrue();
        hub.unregister(second);
        assertThat(hub.isAdminOnline(7L)).isFalse();
    }

    @Test
    void rejectsCustomerServicePresenceFromConnectionsWithoutCustomerServicePermission() {
        RealtimeSessionHub hub = new RealtimeSessionHub(objectMapper);
        WebSocketSession app = session("app-presence");
        WebSocketSession admin = session("admin-without-service-permission");
        hub.register(app, principal(TokenKind.APP, 42L, List.of()));
        hub.register(admin, principal(TokenKind.ADMIN, 7L, List.of("order:read")));

        assertThat(hub.startCustomerServicePresence(app)).isNull();
        assertThat(hub.startCustomerServicePresence(admin)).isNull();
        assertThat(hub.isCustomerServiceAgentOnline(7L)).isFalse();
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private RealtimeConnectionPrincipal principal(TokenKind kind, Long subjectId, List<String> permissions) {
        return new RealtimeConnectionPrincipal(kind, subjectId, "subject-" + subjectId, permissions);
    }

    private TextMessage captureMessage(WebSocketSession session) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(org.springframework.web.socket.WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        return (TextMessage) captor.getValue();
    }
}
