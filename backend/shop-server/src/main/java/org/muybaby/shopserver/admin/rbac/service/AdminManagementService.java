package org.muybaby.shopserver.admin.rbac.service;

import org.muybaby.shopserver.admin.rbac.dto.AdminRoleGrantRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleGrantResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleQueryRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleUpsertRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserCreateRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserQueryRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserUpdateRequest;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
public class AdminManagementService {

    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public AdminManagementService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PasswordEncoder passwordEncoder
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public PageResult<AdminUserResponse> userPage(AdminUserQueryRequest query) {
        long current = normalizeCurrent(query.current());
        long size = normalizeSize(query.size());
        long offset = (current - 1) * size;
        String username = normalize(query.username());
        String email = normalize(query.email());
        String status = normalize(query.status()).toUpperCase();

        long total = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM admin_user u
                        WHERE (:username = '' OR LOWER(u.username) LIKE LOWER(:usernamePattern))
                          AND (:email = '' OR LOWER(u.email) LIKE LOWER(:emailPattern))
                          AND (:status = '' OR u.status = :status)
                        """)
                .param("username", username)
                .param("usernamePattern", "%" + username + "%")
                .param("email", email)
                .param("emailPattern", "%" + email + "%")
                .param("status", status)
                .query(Long.class)
                .single();

        List<AdminUserResponse> records = jdbcClient.sql("""
                        SELECT u.id, u.username, u.display_name, u.email, u.avatar, u.status,
                               u.last_login_at, u.created_at, u.updated_at
                        FROM admin_user u
                        WHERE (:username = '' OR LOWER(u.username) LIKE LOWER(:usernamePattern))
                          AND (:email = '' OR LOWER(u.email) LIKE LOWER(:emailPattern))
                          AND (:status = '' OR u.status = :status)
                        ORDER BY u.id
                        LIMIT :size OFFSET :offset
                        """)
                .param("username", username)
                .param("usernamePattern", "%" + username + "%")
                .param("email", email)
                .param("emailPattern", "%" + email + "%")
                .param("status", status)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapUserRow)
                .list()
                .stream()
                .map(this::toUserResponse)
                .toList();

        return PageResult.of(records, total, current, size);
    }

    @Transactional
    public Long createUser(AdminUserCreateRequest request) {
        String username = request.username().trim();
        if (usernameExists(username, null)) {
            throw new BusinessException(ErrorCode.ADMIN_USERNAME_CONFLICT);
        }
        List<Long> roleIds = distinctIds(request.roleIds());
        requireRoles(roleIds);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO admin_user
                            (username, password_hash, display_name, email, avatar, status, created_at, updated_at)
                        VALUES
                            (:username, :passwordHash, :displayName, :email, :avatar, 'ENABLED', :now, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("username", username)
                        .addValue("passwordHash", passwordEncoder.encode(request.password()))
                        .addValue("displayName", request.displayName().trim())
                        .addValue("email", request.email().trim())
                        .addValue("avatar", normalize(request.avatar()))
                        .addValue("now", LocalDateTime.now()),
                keyHolder,
                new String[]{"id"});
        Long userId = requireGeneratedId(keyHolder);
        replaceUserRoles(userId, roleIds);
        return userId;
    }

    @Transactional
    public void updateUser(Long operatorUserId, Long userId, AdminUserUpdateRequest request) {
        requireUser(userId);
        if (userId.equals(operatorUserId) && "DISABLED".equals(request.status())) {
            throw new BusinessException(ErrorCode.CURRENT_ADMIN_DISABLE_FORBIDDEN);
        }
        List<Long> roleIds = distinctIds(request.roleIds());
        requireRoles(roleIds);
        LocalDateTime now = LocalDateTime.now();

        int updated;
        if (StringUtils.hasText(request.password())) {
            updated = jdbcClient.sql("""
                            UPDATE admin_user
                            SET display_name = :displayName,
                                email = :email,
                                password_hash = :passwordHash,
                                avatar = :avatar,
                                status = :status,
                                updated_at = :now
                            WHERE id = :userId
                            """)
                    .param("displayName", request.displayName().trim())
                    .param("email", request.email().trim())
                    .param("passwordHash", passwordEncoder.encode(request.password()))
                    .param("avatar", normalize(request.avatar()))
                    .param("status", request.status())
                    .param("now", now)
                    .param("userId", userId)
                    .update();
        } else {
            updated = jdbcClient.sql("""
                            UPDATE admin_user
                            SET display_name = :displayName,
                                email = :email,
                                avatar = :avatar,
                                status = :status,
                                updated_at = :now
                            WHERE id = :userId
                            """)
                    .param("displayName", request.displayName().trim())
                    .param("email", request.email().trim())
                    .param("avatar", normalize(request.avatar()))
                    .param("status", request.status())
                    .param("now", now)
                    .param("userId", userId)
                    .update();
        }
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        replaceUserRoles(userId, roleIds);
    }

    @Transactional
    public void disableUser(Long operatorUserId, Long userId) {
        if (userId.equals(operatorUserId)) {
            throw new BusinessException(ErrorCode.CURRENT_ADMIN_DISABLE_FORBIDDEN);
        }
        int updated = jdbcClient.sql("""
                        UPDATE admin_user
                        SET status = 'DISABLED', updated_at = :now
                        WHERE id = :userId AND status <> 'DISABLED'
                        """)
                .param("now", LocalDateTime.now())
                .param("userId", userId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    public PageResult<AdminRoleResponse> rolePage(AdminRoleQueryRequest query) {
        long current = normalizeCurrent(query.current());
        long size = normalizeSize(query.size());
        long offset = (current - 1) * size;
        String name = normalize(query.name());
        String code = normalize(query.code());
        boolean enabledFilter = query.enabled() != null;
        boolean enabled = Boolean.TRUE.equals(query.enabled());
        LocalDateTime startAt = startAt(query.startTime());
        LocalDateTime endExclusive = endExclusive(query.endTime());

        long total = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM admin_role r
                        WHERE (:name = '' OR LOWER(r.name) LIKE LOWER(:namePattern))
                          AND (:code = '' OR LOWER(r.code) LIKE LOWER(:codePattern))
                          AND (:enabledFilter = FALSE OR r.enabled = :enabled)
                          AND r.created_at >= :startAt
                          AND r.created_at < :endExclusive
                        """)
                .param("name", name)
                .param("namePattern", "%" + name + "%")
                .param("code", code)
                .param("codePattern", "%" + code + "%")
                .param("enabledFilter", enabledFilter)
                .param("enabled", enabled)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .query(Long.class)
                .single();

        List<AdminRoleResponse> records = jdbcClient.sql("""
                        SELECT r.id, r.code, r.name, r.description, r.enabled, r.created_at, r.updated_at
                        FROM admin_role r
                        WHERE (:name = '' OR LOWER(r.name) LIKE LOWER(:namePattern))
                          AND (:code = '' OR LOWER(r.code) LIKE LOWER(:codePattern))
                          AND (:enabledFilter = FALSE OR r.enabled = :enabled)
                          AND r.created_at >= :startAt
                          AND r.created_at < :endExclusive
                        ORDER BY r.id
                        LIMIT :size OFFSET :offset
                        """)
                .param("name", name)
                .param("namePattern", "%" + name + "%")
                .param("code", code)
                .param("codePattern", "%" + code + "%")
                .param("enabledFilter", enabledFilter)
                .param("enabled", enabled)
                .param("startAt", startAt)
                .param("endExclusive", endExclusive)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapRoleResponse)
                .list();

        return PageResult.of(records, total, current, size);
    }

    @Transactional
    public Long createRole(AdminRoleUpsertRequest request) {
        String code = request.code().trim();
        if (roleCodeExists(code, null)) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_CODE_CONFLICT);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO admin_role (code, name, description, enabled, created_at, updated_at)
                        VALUES (:code, :name, :description, :enabled, :now, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("name", request.name().trim())
                        .addValue("description", normalize(request.description()))
                        .addValue("enabled", request.enabled())
                        .addValue("now", LocalDateTime.now()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    @Transactional
    public void updateRole(Long roleId, AdminRoleUpsertRequest request) {
        requireRole(roleId);
        String code = request.code().trim();
        if (roleCodeExists(code, roleId)) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_CODE_CONFLICT);
        }
        int updated = jdbcClient.sql("""
                        UPDATE admin_role
                        SET code = :code,
                            name = :name,
                            description = :description,
                            enabled = :enabled,
                            updated_at = :now
                        WHERE id = :roleId
                        """)
                .param("code", code)
                .param("name", request.name().trim())
                .param("description", normalize(request.description()))
                .param("enabled", request.enabled())
                .param("now", LocalDateTime.now())
                .param("roleId", roleId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_UNAVAILABLE);
        }
    }

    @Transactional
    public void deleteRole(Long roleId) {
        requireRole(roleId);
        long assignedUsers = jdbcClient.sql("SELECT COUNT(*) FROM admin_user_role WHERE role_id = :roleId")
                .param("roleId", roleId)
                .query(Long.class)
                .single();
        if (assignedUsers > 0) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_IN_USE);
        }
        jdbcClient.sql("DELETE FROM admin_role_permission WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        jdbcClient.sql("DELETE FROM admin_role_menu WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        int deleted = jdbcClient.sql("DELETE FROM admin_role WHERE id = :roleId")
                .param("roleId", roleId)
                .update();
        if (deleted != 1) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_UNAVAILABLE);
        }
    }

    public AdminRoleGrantResponse roleGrants(Long roleId) {
        requireRole(roleId);
        List<Long> menuIds = jdbcClient.sql("""
                        SELECT menu_id FROM admin_role_menu WHERE role_id = :roleId ORDER BY menu_id
                        """)
                .param("roleId", roleId)
                .query(Long.class)
                .list();
        List<Long> permissionIds = jdbcClient.sql("""
                        SELECT permission_id FROM admin_role_permission WHERE role_id = :roleId ORDER BY permission_id
                        """)
                .param("roleId", roleId)
                .query(Long.class)
                .list();
        return new AdminRoleGrantResponse(roleId, menuIds, permissionIds);
    }

    @Transactional
    public void updateRoleGrants(Long roleId, AdminRoleGrantRequest request) {
        requireRole(roleId);
        List<Long> menuIds = distinctIds(request.menuIds());
        List<Long> permissionIds = distinctIds(request.permissionIds());
        requireIdsExist("admin_menu", menuIds, ErrorCode.ADMIN_ROLE_UNAVAILABLE);
        requireIdsExist("admin_permission", permissionIds, ErrorCode.ADMIN_ROLE_UNAVAILABLE);

        jdbcClient.sql("DELETE FROM admin_role_menu WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        for (Long menuId : menuIds) {
            jdbcClient.sql("INSERT INTO admin_role_menu (role_id, menu_id) VALUES (:roleId, :menuId)")
                    .param("roleId", roleId)
                    .param("menuId", menuId)
                    .update();
        }

        jdbcClient.sql("DELETE FROM admin_role_permission WHERE role_id = :roleId")
                .param("roleId", roleId)
                .update();
        for (Long permissionId : permissionIds) {
            jdbcClient.sql("""
                            INSERT INTO admin_role_permission (role_id, permission_id)
                            VALUES (:roleId, :permissionId)
                            """)
                    .param("roleId", roleId)
                    .param("permissionId", permissionId)
                    .update();
        }
    }

    private AdminUserResponse toUserResponse(UserRow row) {
        List<Long> roleIds = jdbcClient.sql("""
                        SELECT r.id
                        FROM admin_role r
                        JOIN admin_user_role ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.id
                        """)
                .param("userId", row.id())
                .query(Long.class)
                .list();
        List<String> roleCodes = jdbcClient.sql("""
                        SELECT r.code
                        FROM admin_role r
                        JOIN admin_user_role ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.id
                        """)
                .param("userId", row.id())
                .query(String.class)
                .list();
        return new AdminUserResponse(
                row.id(), row.username(), row.displayName(), row.email(), row.avatar(), row.status(),
                roleIds, roleCodes, row.lastLoginAt(), row.createdAt(), row.updatedAt()
        );
    }

    private void replaceUserRoles(Long userId, List<Long> roleIds) {
        jdbcClient.sql("DELETE FROM admin_user_role WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        for (Long roleId : roleIds) {
            jdbcClient.sql("INSERT INTO admin_user_role (user_id, role_id) VALUES (:userId, :roleId)")
                    .param("userId", userId)
                    .param("roleId", roleId)
                    .update();
        }
    }

    private void requireUser(Long userId) {
        long count = jdbcClient.sql("SELECT COUNT(*) FROM admin_user WHERE id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    private void requireRole(Long roleId) {
        long count = jdbcClient.sql("SELECT COUNT(*) FROM admin_role WHERE id = :roleId")
                .param("roleId", roleId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_UNAVAILABLE);
        }
    }

    private void requireRoles(List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_UNAVAILABLE);
        }
        requireIdsExist("admin_role", roleIds, ErrorCode.ADMIN_ROLE_UNAVAILABLE);
    }

    private void requireIdsExist(String tableName, List<Long> ids, ErrorCode errorCode) {
        if (ids.isEmpty()) {
            return;
        }
        Integer count = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                Integer.class
        );
        if (count == null || count != ids.size()) {
            throw new BusinessException(errorCode);
        }
    }

    private boolean usernameExists(String username, Long excludedUserId) {
        long excluded = excludedUserId == null ? -1L : excludedUserId;
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM admin_user
                        WHERE LOWER(username) = LOWER(:username) AND id <> :excluded
                        """)
                .param("username", username)
                .param("excluded", excluded)
                .query(Long.class)
                .single() > 0;
    }

    private boolean roleCodeExists(String code, Long excludedRoleId) {
        long excluded = excludedRoleId == null ? -1L : excludedRoleId;
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM admin_role
                        WHERE LOWER(code) = LOWER(:code) AND id <> :excluded
                        """)
                .param("code", code)
                .param("excluded", excluded)
                .query(Long.class)
                .single() > 0;
    }

    private UserRow mapUserRow(ResultSet rs, int rowNum) throws SQLException {
        return new UserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("avatar"),
                rs.getString("status"),
                timestamp(rs, "last_login_at"),
                timestamp(rs, "created_at"),
                timestamp(rs, "updated_at")
        );
    }

    private AdminRoleResponse mapRoleResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminRoleResponse(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                timestamp(rs, "created_at"),
                timestamp(rs, "updated_at")
        );
    }

    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
    }

    private long normalizeCurrent(Long current) {
        return current == null || current < 1 ? 1L : current;
    }

    private long normalizeSize(Long size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private List<Long> distinctIds(Collection<Long> ids) {
        return ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    }

    private LocalDateTime startAt(LocalDate value) {
        return value == null ? LocalDate.of(1970, 1, 1).atStartOfDay() : value.atStartOfDay();
    }

    private LocalDateTime endExclusive(LocalDate value) {
        return value == null ? LocalDate.of(9999, 12, 31).atStartOfDay() : value.plusDays(1).atStartOfDay();
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Generated admin management id is missing");
        }
        return key.longValue();
    }

    private record UserRow(
            Long id,
            String username,
            String displayName,
            String email,
            String avatar,
            String status,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
