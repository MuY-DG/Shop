package org.muybaby.shopserver.realtime;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeTicketService {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final Map<String, TicketGrant> tickets = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;

    public RealtimeTicketService() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    RealtimeTicketService(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public RealtimeTicketResponse issue(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() == null || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        cleanupExpired();
        String ticket = nextTicket();
        Instant expiresAt = clock.instant().plus(ttl);
        tickets.put(ticket, new TicketGrant(
                new RealtimeConnectionPrincipal(
                        principal.kind(),
                        principal.subjectId(),
                        principal.subjectName(),
                        principal.permissions()
                ),
                expiresAt
        ));
        return new RealtimeTicketResponse(ticket, ttl.toSeconds());
    }

    public Optional<RealtimeConnectionPrincipal> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        TicketGrant grant = tickets.remove(ticket);
        if (grant == null || !grant.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(grant.principal());
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String nextTicket() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record TicketGrant(RealtimeConnectionPrincipal principal, Instant expiresAt) {
    }
}
