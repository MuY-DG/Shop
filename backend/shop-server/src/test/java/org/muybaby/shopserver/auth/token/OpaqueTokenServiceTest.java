package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaqueTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTokenStore tokenStore = new InMemoryTokenStore(clock);
    private final TokenProperties properties = new TokenProperties(
            Duration.ofHours(2),
            Duration.ofDays(7),
            Duration.ofDays(7),
            Duration.ofDays(30)
    );
    private final OpaqueTokenService tokenService = new OpaqueTokenService(tokenStore, properties);

    @Test
    void issueAdminTokensWithAdminPrefixesAndLookupSessionByAccessToken() {
        TokenSession session = TokenSession.admin(1L, "Super", List.of("R_SUPER"), List.of("system:user:create"), clock.instant());

        TokenPair pair = tokenService.issue(TokenKind.ADMIN, session);

        assertThat(pair.accessToken()).startsWith("adm_");
        assertThat(pair.refreshToken()).startsWith("adr_");
        assertThat(pair.expiresIn()).isEqualTo(7200);
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.ADMIN)).contains(session);
    }

    @Test
    void issueAppTokensWithAppPrefixesAndRejectAdminLookup() {
        TokenSession session = TokenSession.app(9L, "openid-user", clock.instant());

        TokenPair pair = tokenService.issue(TokenKind.APP, session);

        assertThat(pair.accessToken()).startsWith("app_");
        assertThat(pair.refreshToken()).startsWith("apr_");
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.ADMIN)).isEmpty();
        assertThat(tokenService.lookupAccessToken(pair.accessToken(), TokenKind.APP)).contains(session);
    }

    @Test
    void rejectMismatchedKindSessionWithoutPersistingTokens() {
        RecordingTokenStore recordingStore = new RecordingTokenStore();
        OpaqueTokenService service = new OpaqueTokenService(recordingStore, properties);
        TokenSession appSession = TokenSession.app(9L, "openid-user", clock.instant());

        assertThatThrownBy(() -> service.issue(TokenKind.ADMIN, appSession))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token session kind does not match requested token kind");

        assertThat(recordingStore.savedKeys()).isEmpty();
    }

    private static class RecordingTokenStore implements TokenStore {

        private final List<String> savedKeys = new ArrayList<>();

        @Override
        public void save(String key, TokenSession session, Duration ttl) {
            savedKeys.add(key);
        }

        @Override
        public Optional<TokenSession> find(String key) {
            return Optional.empty();
        }

        @Override
        public void delete(String key) {
        }

        List<String> savedKeys() {
            return savedKeys;
        }
    }
}
