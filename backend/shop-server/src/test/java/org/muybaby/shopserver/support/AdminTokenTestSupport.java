package org.muybaby.shopserver.support;

import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class AdminTokenTestSupport {

    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    private AdminTokenTestSupport() {
    }

    public static String issueAdminToken(
            JdbcClient jdbcClient,
            OpaqueTokenService opaqueTokenService,
            List<String> permissions
    ) {
        long fixtureSequence = FIXTURE_SEQUENCE.incrementAndGet();
        String username = "test-admin-" + fixtureSequence;
        String roleCode = "R_TEST_" + fixtureSequence;

        jdbcClient.sql("""
                        insert into admin_user
                            (username, password_hash, display_name, email, status)
                        values
                            (:username, 'test-only-not-used', :username, :email, 'ENABLED')
                        """)
                .param("username", username)
                .param("email", username + "@shop.test")
                .update();
        long userId = jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_role (code, name, description, enabled)
                        values (:roleCode, :roleCode, '', true)
                        """)
                .param("roleCode", roleCode)
                .update();
        long roleId = jdbcClient.sql("select id from admin_role where code = :roleCode")
                .param("roleCode", roleCode)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:userId, :roleId)
                        """)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        for (String permission : permissions) {
            Long permissionId = jdbcClient.sql("""
                            select id
                            from admin_permission
                            where auth_mark = :permission
                            """)
                    .param("permission", permission)
                    .query(Long.class)
                    .single();
            jdbcClient.sql("""
                            insert into admin_role_permission (role_id, permission_id)
                            values (:roleId, :permissionId)
                            """)
                    .param("roleId", roleId)
                    .param("permissionId", permissionId)
                    .update();
        }

        TokenSession session = TokenSession.admin(
                userId,
                username,
                List.of(roleCode),
                List.copyOf(permissions),
                Instant.now()
        );
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }
}
