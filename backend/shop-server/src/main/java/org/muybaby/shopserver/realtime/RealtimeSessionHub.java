package org.muybaby.shopserver.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Component
public class RealtimeSessionHub {

    private final ObjectMapper objectMapper;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public RealtimeSessionHub(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session, RealtimeConnectionPrincipal principal) {
        connections.put(session.getId(), new Connection(session, principal));
    }

    public void unregister(WebSocketSession session) {
        connections.remove(session.getId());
    }

    public void sendToAppUser(Long appUserId, String type, Object data) {
        send(type, data, connection -> connection.principal().kind() == TokenKind.APP
                && appUserId.equals(connection.principal().subjectId()));
    }

    public void sendToAdminsWithPermission(String permission, String type, Object data) {
        send(type, data, connection -> connection.principal().kind() == TokenKind.ADMIN
                && connection.principal().permissions().contains(permission));
    }

    public void sendToAdminsMatching(
            String type,
            Object data,
            Predicate<RealtimeConnectionPrincipal> audience
    ) {
        send(type, data, connection -> connection.principal().kind() == TokenKind.ADMIN
                && audience.test(connection.principal()));
    }

    public void sendToAdminUser(Long adminUserId, String type, Object data) {
        send(type, data, connection -> connection.principal().kind() == TokenKind.ADMIN
                && adminUserId.equals(connection.principal().subjectId()));
    }

    public boolean isAdminOnline(Long adminUserId) {
        if (adminUserId == null) {
            return false;
        }
        return connections.values().stream().anyMatch(connection -> {
            if (!connection.session().isOpen()) {
                connections.remove(connection.session().getId());
                return false;
            }
            return connection.principal().kind() == TokenKind.ADMIN
                    && adminUserId.equals(connection.principal().subjectId());
        });
    }

    public void sendPong(WebSocketSession session) {
        sendOne(session, new RealtimeEnvelope(
                UUID.randomUUID().toString(), "PONG", Instant.now(), Map.of()
        ));
    }

    private void send(String type, Object data, Predicate<Connection> audience) {
        RealtimeEnvelope envelope = new RealtimeEnvelope(
                UUID.randomUUID().toString(), type, Instant.now(), data
        );
        connections.values().stream()
                .filter(audience)
                .forEach(connection -> sendOne(connection.session(), envelope));
    }

    private void sendOne(WebSocketSession session, RealtimeEnvelope envelope) {
        if (!session.isOpen()) {
            connections.remove(session.getId());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(envelope);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException ex) {
            connections.remove(session.getId());
        }
    }

    private record Connection(WebSocketSession session, RealtimeConnectionPrincipal principal) {
    }
}
