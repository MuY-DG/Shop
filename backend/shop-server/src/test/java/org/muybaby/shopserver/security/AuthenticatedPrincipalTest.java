package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class AuthenticatedPrincipalTest {

    @Test
    void normalizesNullRolesAndPermissions() {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(TokenKind.ADMIN, 1L, "admin", null, null);

        assertThat(principal.roles()).isEmpty();
        assertThat(principal.permissions()).isEmpty();
    }

    @Test
    void defensivelyCopiesRolesAndPermissions() {
        List<String> roles = new ArrayList<>(List.of("R_SUPER"));
        List<String> permissions = new ArrayList<>(List.of("product:read"));

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(TokenKind.ADMIN, 1L, "admin", roles, permissions);
        roles.add("R_MANAGER");
        permissions.add("product:write");

        assertThat(principal.roles()).containsExactly("R_SUPER");
        assertThat(principal.permissions()).containsExactly("product:read");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> principal.roles().add("R_MANAGER"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> principal.permissions().add("product:write"));
    }
}
