package org.muybaby.shopserver.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Component
public class RealtimeSessionHub {

    static final Duration CUSTOMER_SERVICE_PRESENCE_TTL = Duration.ofSeconds(60);
    private static final String CUSTOMER_SERVICE_READ_PERMISSION =
            "customer-service:conversation:read";

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

    /**
     * Marks this particular realtime connection as an active customer-service workspace.
     *
     * @return the admin id when this is the first live workspace for that admin, otherwise null
     */
    public synchronized Long startCustomerServicePresence(WebSocketSession session) {
        Connection connection = connections.get(session.getId());
        if (!canExposeCustomerServicePresence(connection)) {
            return null;
        }
        Long adminUserId = connection.principal().subjectId();
        boolean wasOnline = isCustomerServiceAgentOnline(adminUserId);
        connection.startCustomerServicePresence(Instant.now());
        return wasOnline ? null : adminUserId;
    }

    public synchronized void stopCustomerServicePresence(WebSocketSession session) {
        Connection connection = connections.get(session.getId());
        if (connection != null) {
            connection.stopCustomerServicePresence();
        }
    }

    /**
     * Refreshes the workspace lease when its shared realtime connection sends a heartbeat.
     *
     * @return the admin id when an expired workspace became live again, otherwise null
     */
    public synchronized Long touchCustomerServicePresence(WebSocketSession session) {
        Connection connection = connections.get(session.getId());
        if (connection == null || !connection.customerServicePresenceRequested()) {
            return null;
        }
        Long adminUserId = connection.principal().subjectId();
        boolean wasOnline = isCustomerServiceAgentOnline(adminUserId);
        connection.touchCustomerServicePresence(Instant.now());
        return wasOnline ? null : adminUserId;
    }

    public boolean isCustomerServiceAgentOnline(Long adminUserId) {
        return isCustomerServiceAgentOnlineAt(adminUserId, Instant.now());
    }

    public boolean isCustomerServicePresenceActive(WebSocketSession session) {
        Connection connection = connections.get(session.getId());
        return canExposeCustomerServicePresence(connection)
                && connection.hasFreshCustomerServicePresence(
                        Instant.now().minus(CUSTOMER_SERVICE_PRESENCE_TTL));
    }

    boolean isCustomerServiceAgentOnlineAt(Long adminUserId, Instant now) {
        if (adminUserId == null) {
            return false;
        }
        Instant cutoff = now.minus(CUSTOMER_SERVICE_PRESENCE_TTL);
        return connections.values().stream().anyMatch(connection -> {
            if (!connection.session().isOpen()) {
                connections.remove(connection.session().getId(), connection);
                return false;
            }
            return connection.principal().kind() == TokenKind.ADMIN
                    && adminUserId.equals(connection.principal().subjectId())
                    && connection.hasFreshCustomerServicePresence(cutoff);
        });
    }

    private boolean canExposeCustomerServicePresence(Connection connection) {
        return connection != null
                && connection.session().isOpen()
                && connection.principal().kind() == TokenKind.ADMIN
                && connection.principal().permissions().contains(
                        CUSTOMER_SERVICE_READ_PERMISSION);
    }

    public void disconnectAdmin(Long adminUserId) {
        if (adminUserId == null) {
            return;
        }
        connections.forEach((sessionId, connection) -> {
            if (connection.principal().kind() != TokenKind.ADMIN
                    || !adminUserId.equals(connection.principal().subjectId())
                    || !connections.remove(sessionId, connection)) {
                return;
            }
            try {
                if (connection.session().isOpen()) {
                    connection.session().close(
                            CloseStatus.POLICY_VIOLATION.withReason("Account roles changed"));
                }
            } catch (IOException ignored) {
                // The connection has already been removed from the live registry.
            }
        });
    }

    public void sendPong(WebSocketSession session) {
        sendOne(session, new RealtimeEnvelope(
                UUID.randomUUID().toString(), "PONG", Instant.now(), Map.of()
        ));
    }

    public void sendCustomerServicePresenceStarted(WebSocketSession session) {
        sendOne(session, new RealtimeEnvelope(
                UUID.randomUUID().toString(),
                "CUSTOMER_SERVICE_PRESENCE_STARTED",
                Instant.now(),
                Map.of()
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

    private static final class Connection {

        private final WebSocketSession session;
        private final RealtimeConnectionPrincipal principal;
        private volatile boolean customerServicePresenceRequested;
        private volatile Instant customerServiceLastSeenAt;

        private Connection(
                WebSocketSession session,
                RealtimeConnectionPrincipal principal
        ) {
            this.session = session;
            this.principal = principal;
        }

        private WebSocketSession session() {
            return session;
        }

        private RealtimeConnectionPrincipal principal() {
            return principal;
        }

        private boolean customerServicePresenceRequested() {
            return customerServicePresenceRequested;
        }

        private void startCustomerServicePresence(Instant now) {
            customerServicePresenceRequested = true;
            customerServiceLastSeenAt = now;
        }

        private void stopCustomerServicePresence() {
            customerServicePresenceRequested = false;
            customerServiceLastSeenAt = null;
        }

        private void touchCustomerServicePresence(Instant now) {
            customerServiceLastSeenAt = now;
        }

        private boolean hasFreshCustomerServicePresence(Instant cutoff) {
            Instant lastSeenAt = customerServiceLastSeenAt;
            return customerServicePresenceRequested
                    && lastSeenAt != null
                    && !lastSeenAt.isBefore(cutoff);
        }
    }
}
