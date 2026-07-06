package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;

import static org.assertj.core.api.Assertions.assertThat;

class PathTokenKindResolverTest {

    private final PathTokenKindResolver resolver = new PathTokenKindResolver();

    @Test
    void resolvesAdminAndAppProtectedPaths() {
        assertThat(resolver.resolve("/admin/auth/current-user")).contains(TokenKind.ADMIN);
        assertThat(resolver.resolve("/app/auth/phone")).contains(TokenKind.APP);
    }

    @Test
    void publicAndCallbackPathsDoNotUseUserTokens() {
        assertThat(resolver.resolve("/admin/auth/login")).isEmpty();
        assertThat(resolver.resolve("/app/auth/login")).isEmpty();
        assertThat(resolver.resolve("/wxpay/notify")).isEmpty();
        assertThat(resolver.resolve("/wechat/events")).isEmpty();
    }
}
