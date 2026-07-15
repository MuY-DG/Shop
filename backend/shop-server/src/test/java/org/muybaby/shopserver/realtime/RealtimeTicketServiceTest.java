package org.muybaby.shopserver.realtime;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeTicketServiceTest {

    @Test
    void ticketIsShortLivedAndCanOnlyBeConsumedOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-15T08:00:00Z"));
        RealtimeTicketService service = new RealtimeTicketService(clock, Duration.ofSeconds(60));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                "session-1",
                TokenKind.ADMIN,
                9L,
                "Agent",
                List.of("R_CUSTOMER_SERVICE"),
                List.of("customer-service:conversation:read")
        );

        RealtimeTicketResponse response = service.issue(principal);

        assertThat(response.expiresIn()).isEqualTo(60);
        assertThat(response.ticket()).doesNotContain("adm_");
        assertThat(service.consume(response.ticket()))
                .get()
                .satisfies(consumed -> {
                    assertThat(consumed.kind()).isEqualTo(TokenKind.ADMIN);
                    assertThat(consumed.subjectId()).isEqualTo(9L);
                    assertThat(consumed.permissions()).containsExactly("customer-service:conversation:read");
                });
        assertThat(service.consume(response.ticket())).isEmpty();

        RealtimeTicketResponse expired = service.issue(principal);
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.consume(expired.ticket())).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
