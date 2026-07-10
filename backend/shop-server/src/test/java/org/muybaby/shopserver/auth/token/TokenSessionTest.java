package org.muybaby.shopserver.auth.token;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TokenSessionTest {

    private static final String CANONICAL_GENERATION_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String UPPERCASE_GENERATION_ID = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF";

    @Test
    void sevenArgumentConstructorFallsBackToSessionIdForLegacyGeneration() {
        TokenSession session = new TokenSession(
                "family-1",
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-06T12:00:00Z")
        );

        assertThat(session.generationId()).isEqualTo("family-1");
    }

    @Test
    void appFactoryCreatesAnIndependentGenerationWithinTheSessionFamily() {
        TokenSession session = TokenSession.app(
                9L,
                "openid",
                Instant.parse("2026-07-06T12:00:00Z")
        );

        assertThat(session.sessionId()).isNotBlank();
        assertThat(session.generationId())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .isNotEqualTo(session.sessionId());
    }

    @Test
    void canonicalUuidGenerationIdsRemainIndependentFromTheSessionFamily() {
        assertThat(sessionWithGeneration(CANONICAL_GENERATION_ID).generationId())
                .isEqualTo(CANONICAL_GENERATION_ID);
        assertThat(sessionWithGeneration(UPPERCASE_GENERATION_ID).generationId())
                .isEqualTo(UPPERCASE_GENERATION_ID);
    }

    @Test
    void invalidLegacyGenerationIdsFallBackToTheSessionId() {
        assertThat(sessionWithGeneration(null).generationId()).isEqualTo("family-1");
        for (String generationId : List.of(
                "",
                "   ",
                "\u2003",
                "not-a-uuid",
                "123e4567e89b12d3a456426614174000",
                "123e4567-e89b-12d3-a456-42661417400g",
                "123e4567-e89b-12d3-a456-426614174000-extra"
        )) {
            assertThat(sessionWithGeneration(generationId).generationId())
                    .as("generationId %s", generationId)
                    .isEqualTo("family-1");
        }
    }

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

    private TokenSession sessionWithGeneration(String generationId) {
        return new TokenSession(
                "family-1",
                generationId,
                TokenKind.APP,
                9L,
                "openid",
                List.of(),
                List.of(),
                Instant.parse("2026-07-06T12:00:00Z")
        );
    }
}
