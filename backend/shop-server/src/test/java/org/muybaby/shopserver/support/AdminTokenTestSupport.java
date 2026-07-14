package org.muybaby.shopserver.support;

import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class AdminTokenTestSupport {

    private static final AtomicLong IDS = new AtomicLong(8_000_000_000L);

    private AdminTokenTestSupport() {
    }

    public static String issueAdminToken(
            JdbcClient jdbcClient,
            OpaqueTokenService opaqueTokenService,
            List<String> permissions
    ) {
        long id = IDS.incrementAndGet();
        String username = "test-admin-" + id;
        String roleCode = "R_TEST_" + id;

        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:id, :username, 'test-only-not-used', :username, :email, 'ENABLED')
                        """)
                .param("id", id)
                .param("username", username)
                .param("email", username + "@shop.test")
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:id, :roleCode, :roleCode, '', true)
                        """)
                .param("id", id)
                .param("roleCode", roleCode)
                .update();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:id, :id)
                        """)
                .param("id", id)
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
                    .param("roleId", id)
                    .param("permissionId", permissionId)
                    .update();
        }

        TokenSession session = TokenSession.admin(
                id,
                username,
                List.of(roleCode),
                List.copyOf(permissions),
                Instant.now()
        );
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }
}
