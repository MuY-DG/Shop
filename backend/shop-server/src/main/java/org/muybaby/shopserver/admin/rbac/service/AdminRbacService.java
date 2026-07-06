package org.muybaby.shopserver.admin.rbac.service;

import org.muybaby.shopserver.admin.rbac.entity.AdminUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Service
public class AdminRbacService {

    private final JdbcClient jdbcClient;

    public AdminRbacService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<AdminUser> findEnabledUserByUsername(String username) {
        return jdbcClient.sql("""
                        select id, username, password_hash, display_name, email, avatar, status,
                               last_login_at, created_at, updated_at
                        from admin_user
                        where username = :username and status = 'ENABLED'
                        """)
                .param("username", username)
                .query(this::mapAdminUser)
                .optional();
    }

    public Optional<AdminUser> findEnabledUserById(Long userId) {
        return jdbcClient.sql("""
                        select id, username, password_hash, display_name, email, avatar, status,
                               last_login_at, created_at, updated_at
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
                        where ur.user_id = :userId and r.enabled = true
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
                        where ur.user_id = :userId and r.enabled = true
                        order by p.auth_mark
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
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
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
