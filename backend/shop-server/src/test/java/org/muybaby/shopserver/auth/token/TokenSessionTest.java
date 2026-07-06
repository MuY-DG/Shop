package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TokenSessionTest {

    @Test
    void defensivelyCopiesRolesAndPermissionsFromPublicConstructor() {
        List<String> roles = new ArrayList<>(List.of("R_SUPER"));
        List<String> permissions = new ArrayList<>(List.of("system:user:create"));

        TokenSession session = new TokenSession(
                "session-1",
                TokenKind.ADMIN,
                1L,
                "Super",
                roles,
                permissions,
                Instant.parse("2026-07-06T12:00:00Z")
        );
        roles.add("R_CHANGED");
        permissions.add("system:user:delete");

        assertThat(session.roles()).containsExactly("R_SUPER");
        assertThat(session.permissions()).containsExactly("system:user:create");
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> session.roles().add("R_OTHER"));
    }

    @Test
    void convertsNullRolesAndPermissionsToEmptyLists() {
        TokenSession session = new TokenSession(
                "session-1",
                TokenKind.ADMIN,
                1L,
                "Super",
                null,
                null,
                Instant.parse("2026-07-06T12:00:00Z")
        );

        assertThat(session.roles()).isEmpty();
        assertThat(session.permissions()).isEmpty();
    }
}
