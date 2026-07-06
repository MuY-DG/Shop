package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenStoreTest {

    @Test
    void expiredSessionsAreNotReturned() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenStore store = new InMemoryTokenStore(clock);
        TokenSession session = TokenSession.admin(1L, "Super", List.of("R_SUPER"), List.of(), clock.instant());

        store.save("shop:auth:admin:access:hash", session, Duration.ZERO);

        assertThat(store.find("shop:auth:admin:access:hash")).isEmpty();
    }
}
