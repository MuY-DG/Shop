package org.muybaby.shopserver.admin.rbac.service;

import org.muybaby.shopserver.admin.rbac.entity.AdminUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class AdminRbacService {

    private final JdbcClient jdbcClient;

    public AdminRbacService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<AdminUser> findEnabledUserByUsername(String username) {
        return jdbcClient.sql("""
                        select id, username, password_hash, display_name, email, avatar, status,
                               max_sessions, auth_version, last_login_at, created_at, updated_at
                        from admin_user
                        where (
                            username_normalized = :usernameNormalized
                            or (username_normalized is null and lower(username) = :usernameNormalized)
                        )
                          and status = 'ENABLED'
                        """)
                .param("usernameNormalized", username.strip().toLowerCase(Locale.ROOT))
                .query(this::mapAdminUser)
                .optional();
    }

    public Optional<AdminUser> findEnabledUserById(Long userId) {
        return jdbcClient.sql("""
                        select id, username, password_hash, display_name, email, avatar, status,
                               max_sessions, auth_version, last_login_at, created_at, updated_at
                        from admin_user
                        where id = :userId and status = 'ENABLED'
                        """)
                .param("userId", userId)
                .query(this::mapAdminUser)
                .optional();
    }

    public List<String> roleCodesByUserId(Long userId) {
        return jdbcClient.sql("""
                        select r.code
                        from admin_role r
                        join admin_user_role ur on ur.role_id = r.id
                        join admin_user u on u.id = ur.user_id
                        where ur.user_id = :userId and u.status = 'ENABLED' and r.enabled = true
                        order by r.id
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    public List<String> permissionMarksByUserId(Long userId) {
        return jdbcClient.sql("""
                        select distinct p.auth_mark
                        from admin_permission p
                        join admin_role_permission rp on rp.permission_id = p.id
                        join admin_user_role ur on ur.role_id = rp.role_id
                        join admin_role r on r.id = ur.role_id
                        join admin_user u on u.id = ur.user_id
                        where ur.user_id = :userId and u.status = 'ENABLED' and r.enabled = true
                        order by p.auth_mark
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    public Optional<AdminAuthorization> findEnabledAuthorizationByUserId(Long userId) {
        List<AuthorizationRow> rows = jdbcClient.sql("""
                        select u.id as user_id,
                               u.username,
                               u.max_sessions,
                               u.auth_version,
                               r.code as role_code,
                               p.auth_mark
                        from admin_user u
                        left join admin_user_role ur on ur.user_id = u.id
                        left join admin_role r on r.id = ur.role_id and r.enabled = true
                        left join admin_role_permission rp on rp.role_id = r.id
                        left join admin_permission p on p.id = rp.permission_id
                        where u.id = :userId and u.status = 'ENABLED'
                        order by r.id, p.auth_mark
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new AuthorizationRow(
                        rs.getLong("user_id"),
                        rs.getString("username"),
                        rs.getInt("max_sessions"),
                        rs.getLong("auth_version"),
                        rs.getString("role_code"),
                        rs.getString("auth_mark")
                ))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Set<String> roles = new LinkedHashSet<>();
        Set<String> permissions = new LinkedHashSet<>();
        for (AuthorizationRow row : rows) {
            if (row.roleCode() != null) {
                roles.add(row.roleCode());
            }
            if (row.authMark() != null) {
                permissions.add(row.authMark());
            }
        }

        AuthorizationRow first = rows.getFirst();
        return Optional.of(new AdminAuthorization(
                first.userId(),
                first.username(),
                List.copyOf(roles),
                List.copyOf(permissions),
                first.maxSessions(),
                first.authVersion()
        ));
    }

    private AdminUser mapAdminUser(ResultSet rs, int rowNum) throws SQLException {
        return new AdminUser(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("avatar"),
                rs.getString("status"),
                rs.getInt("max_sessions"),
                rs.getLong("auth_version"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private record AuthorizationRow(
            Long userId,
            String username,
            int maxSessions,
            long authVersion,
            String roleCode,
            String authMark
    ) {
    }
}
