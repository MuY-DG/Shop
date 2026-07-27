package org.muybaby.shopserver.auth.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.AccountSession;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenPair;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminSessionLimitReconcilerTest {

    private static final long USER_ID = 9_520_001L;

    @Autowired
    private AdminSessionLimitReconciler reconciler;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        cleanUp();
        String passwordHash = jdbcClient.sql("SELECT password_hash FROM admin_user WHERE id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO admin_user
                            (id, username, password_hash, display_name, email, avatar, status,
                             max_sessions)
                        VALUES
                            (:userId, 'SessionReconcileAdmin', :passwordHash,
                             'Session Reconcile Admin', 'session-reconcile-admin@shop.local',
                             '', 'ENABLED', 0)
                        """)
                .param("userId", USER_ID)
                .param("passwordHash", passwordHash)
                .update();
    }

    @AfterEach
    void cleanUp() {
        opaqueTokenService.revokeSubjectSessions(TokenKind.ADMIN, USER_ID);
        jdbcClient.sql("DELETE FROM admin_user_role WHERE user_id = :userId")
                .param("userId", USER_ID)
                .update();
        jdbcClient.sql("DELETE FROM admin_user WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
    }

    @Test
    void eventuallyEnforcesTheCurrentDatabaseLimitAndKeepsTheNewestSession() {
        TokenPair oldest = register(
                "reconcile-oldest",
                "device-oldest",
                Instant.parse("2026-07-27T01:00:00Z")
        );
        TokenPair newest = register(
                "reconcile-newest",
                "device-newest",
                Instant.parse("2026-07-27T02:00:00Z")
        );
        assertThat(opaqueTokenService.listSessions(TokenKind.ADMIN, USER_ID)).hasSize(2);

        jdbcClient.sql("UPDATE admin_user SET max_sessions = 1 WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
        reconciler.reconcileUser(USER_ID);
        reconciler.reconcileUser(USER_ID);

        assertThat(opaqueTokenService.listSessions(TokenKind.ADMIN, USER_ID))
                .extracting(AccountSession::sessionId)
                .containsExactly("reconcile-newest");
        assertThat(opaqueTokenService.lookupAccessToken(oldest.accessToken(), TokenKind.ADMIN))
                .isEmpty();
        assertThat(opaqueTokenService.lookupAccessToken(newest.accessToken(), TokenKind.ADMIN))
                .isPresent();
    }

    @Test
    void delayedReconciliationDoesNotApplyAnObsoleteRestrictedLimit() {
        TokenPair first = register(
                "reconcile-unlimited-1",
                "device-unlimited-1",
                Instant.parse("2026-07-27T01:00:00Z")
        );
        TokenPair second = register(
                "reconcile-unlimited-2",
                "device-unlimited-2",
                Instant.parse("2026-07-27T02:00:00Z")
        );

        // Simulates a delayed event whose snapshot was restrictive, after the current
        // database policy has already been raised back to unlimited.
        jdbcClient.sql("UPDATE admin_user SET max_sessions = 0 WHERE id = :userId")
                .param("userId", USER_ID)
                .update();
        reconciler.reconcileUser(USER_ID);

        assertThat(opaqueTokenService.listSessions(TokenKind.ADMIN, USER_ID)).hasSize(2);
        assertThat(opaqueTokenService.lookupAccessToken(first.accessToken(), TokenKind.ADMIN))
                .isPresent();
        assertThat(opaqueTokenService.lookupAccessToken(second.accessToken(), TokenKind.ADMIN))
                .isPresent();
    }

    private TokenPair register(String sessionId, String deviceId, Instant loginAt) {
        TokenSession tokenSession = TokenSession.admin(
                sessionId,
                USER_ID,
                "SessionReconcileAdmin",
                List.of("R_ADMIN"),
                List.of(),
                loginAt
        );
        AccountSession accountSession = new AccountSession(
                sessionId,
                TokenKind.ADMIN,
                USER_ID,
                "SessionReconcileAdmin",
                deviceId,
                "198.51.100.8",
                "Mozilla/5.0",
                loginAt,
                loginAt
        );
        return opaqueTokenService.issueRegistered(
                TokenKind.ADMIN,
                tokenSession,
                accountSession,
                0
        );
    }
}
