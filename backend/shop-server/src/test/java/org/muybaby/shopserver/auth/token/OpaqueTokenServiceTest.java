package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-06T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryTokenStore tokenStore = new InMemoryTokenStore(clock);
    private final TokenProperties properties = new TokenProperties(
            Duration.ofHours(2),
            Duration.ofDays(7),
            Duration.ofDays(7),
            Duration.ofDays(30)
    );
    private final OpaqueTokenService tokenService = new OpaqueTokenService(tokenStore, properties, clock);

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
}
