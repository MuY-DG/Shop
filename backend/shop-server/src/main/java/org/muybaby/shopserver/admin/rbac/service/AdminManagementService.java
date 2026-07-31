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
import org.muybaby.shopserver.auth.session.AdminSessionPolicyChangedEvent;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminManagementService {

    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final Set<String> CUSTOMER_SERVICE_ROLE_CODES = Set.of(
            "R_CUSTOMER_SERVICE", "R_CUSTOMER_SERVICE_MANAGER");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public AdminManagementService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
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

        List<UserRow> userRows = jdbcClient.sql("""
                        SELECT u.id, u.username, u.display_name, u.email, u.avatar, u.status,
                               u.max_sessions,
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
                .list();
        Map<Long, List<UserRoleRow>> rolesByUserId = rolesByUserId(
                userRows.stream().map(UserRow::id).toList());
        List<AdminUserResponse> records = userRows.stream()
                .map(row -> toUserResponse(row, rolesByUserId.getOrDefault(row.id(), List.of())))
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
        requireGeneralCreateRoleSelection(roleIds);
        int maxSessions = normalizeMaxSessions(request.maxSessions(), 0);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO admin_user
                            (username, password_hash, display_name, email, avatar, status,
                             max_sessions, created_at, updated_at)
                        VALUES
                            (:username, :passwordHash, :displayName, :email, :avatar, 'ENABLED',
                             :maxSessions, :now, :now)
                        """,
                new MapSqlParameterSource()
                        .addValue("username", username)
                        .addValue("passwordHash", passwordEncoder.encode(request.password()))
                        .addValue("displayName", request.displayName().trim())
                        .addValue("email", request.email().trim())
                        .addValue("avatar", normalize(request.avatar()))
                        .addValue("maxSessions", maxSessions)
                        .addValue("now", LocalDateTime.now()),
                keyHolder,
                new String[]{"id"});
        Long userId = requireGeneratedId(keyHolder);
        replaceUserRoles(userId, roleIds);
        return userId;
    }

    @Transactional
    public void updateUser(Long operatorUserId, Long userId, AdminUserUpdateRequest request) {
        UserSecurityState current = requireUserSecurityStateForUpdate(userId);
        if (userId.equals(operatorUserId) && "DISABLED".equals(request.status())) {
            throw new BusinessException(ErrorCode.CURRENT_ADMIN_DISABLE_FORBIDDEN);
        }
        List<Long> roleIds = distinctIds(request.roleIds());
        requireRoles(roleIds);
        requireGeneralUpdateRoleSelection(userId, roleIds);
        LocalDateTime now = LocalDateTime.now();
        boolean passwordChanged = StringUtils.hasText(request.password());
        boolean disabled = !"DISABLED".equals(current.status()) && "DISABLED".equals(request.status());
        boolean revokeAll = passwordChanged || disabled;
        int maxSessions = normalizeMaxSessions(request.maxSessions(), current.maxSessions());
        boolean sessionPolicyChanged = maxSessions != current.maxSessions();
        int authVersionIncrement = revokeAll ? 1 : 0;

        int updated;
        if (passwordChanged) {
            updated = jdbcClient.sql("""
                            UPDATE admin_user
                            SET display_name = :displayName,
                                email = :email,
                                password_hash = :passwordHash,
                                avatar = :avatar,
                                status = :status,
                                max_sessions = :maxSessions,
                                auth_version = auth_version + :authVersionIncrement,
                                updated_at = :now
                            WHERE id = :userId
                            """)
                    .param("displayName", request.displayName().trim())
                    .param("email", request.email().trim())
                    .param("passwordHash", passwordEncoder.encode(request.password()))
                    .param("avatar", normalize(request.avatar()))
                    .param("status", request.status())
                    .param("maxSessions", maxSessions)
                    .param("authVersionIncrement", authVersionIncrement)
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
                                max_sessions = :maxSessions,
                                auth_version = auth_version + :authVersionIncrement,
                                updated_at = :now
                            WHERE id = :userId
                            """)
                    .param("displayName", request.displayName().trim())
                    .param("email", request.email().trim())
                    .param("avatar", normalize(request.avatar()))
                    .param("status", request.status())
                    .param("maxSessions", maxSessions)
                    .param("authVersionIncrement", authVersionIncrement)
                    .param("now", now)
                    .param("userId", userId)
                    .update();
        }
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        replaceUserRoles(userId, roleIds);
        publishSessionPolicyChange(userId, revokeAll, sessionPolicyChanged, maxSessions);
    }

    @Transactional
    public void disableUser(Long operatorUserId, Long userId) {
        if (userId.equals(operatorUserId)) {
            throw new BusinessException(ErrorCode.CURRENT_ADMIN_DISABLE_FORBIDDEN);
        }
        UserSecurityState current = requireUserSecurityStateForUpdate(userId);
        int updated = jdbcClient.sql("""
                        UPDATE admin_user
                        SET status = 'DISABLED',
                            auth_version = auth_version + 1,
                            updated_at = :now
                        WHERE id = :userId AND status <> 'DISABLED'
                        """)
                .param("now", LocalDateTime.now())
                .param("userId", userId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        eventPublisher.publishEvent(new AdminSessionPolicyChangedEvent(
                userId,
                true,
                current.maxSessions()
        ));
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
        validateRoleGrantConsistency(menuIds, permissionIds);

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

    private void validateRoleGrantConsistency(List<Long> menuIds, List<Long> permissionIds) {
        if (menuIds.isEmpty()) {
            if (!permissionIds.isEmpty()) {
                throw new BusinessException(ErrorCode.ADMIN_ROLE_GRANT_INVALID);
            }
            return;
        }

        Integer menusMissingParents = namedParameterJdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM admin_menu menu_item
                        WHERE menu_item.id IN (:menuIds)
                          AND menu_item.parent_id IS NOT NULL
                          AND menu_item.parent_id NOT IN (:menuIds)
                        """,
                new MapSqlParameterSource("menuIds", menuIds),
                Integer.class
        );
        if (menusMissingParents == null || menusMissingParents > 0) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_GRANT_INVALID);
        }

        if (permissionIds.isEmpty()) {
            return;
        }
        Integer permissionsWithOwningMenus = namedParameterJdbcTemplate.queryForObject("""
                        SELECT COUNT(DISTINCT menu_permission.permission_id)
                        FROM admin_menu_permission menu_permission
                        WHERE menu_permission.permission_id IN (:permissionIds)
                          AND menu_permission.menu_id IN (:menuIds)
                        """,
                new MapSqlParameterSource()
                        .addValue("permissionIds", permissionIds)
                        .addValue("menuIds", menuIds),
                Integer.class
        );
        if (permissionsWithOwningMenus == null || permissionsWithOwningMenus != permissionIds.size()) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_GRANT_INVALID);
        }
    }

    private Map<Long, List<UserRoleRow>> rolesByUserId(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRoleRow> roleRows = jdbcClient.sql("""
                        SELECT ur.user_id, r.id AS role_id, r.code AS role_code
                        FROM admin_user_role ur
                        JOIN admin_role r ON r.id = ur.role_id
                        WHERE ur.user_id IN (:userIds)
                        ORDER BY ur.user_id, r.id
                        """)
                .param("userIds", userIds)
                .query((rs, rowNum) -> new UserRoleRow(
                        rs.getLong("user_id"),
                        rs.getLong("role_id"),
                        rs.getString("role_code")
                ))
                .list();
        Map<Long, List<UserRoleRow>> rolesByUserId = new LinkedHashMap<>();
        for (UserRoleRow roleRow : roleRows) {
            rolesByUserId.computeIfAbsent(roleRow.userId(), ignored -> new java.util.ArrayList<>())
                    .add(roleRow);
        }
        rolesByUserId.replaceAll((userId, roles) -> List.copyOf(roles));
        return Map.copyOf(rolesByUserId);
    }

    private AdminUserResponse toUserResponse(UserRow row, List<UserRoleRow> roles) {
        List<Long> roleIds = roles.stream().map(UserRoleRow::roleId).toList();
        List<String> roleCodes = roles.stream().map(UserRoleRow::roleCode).toList();
        return new AdminUserResponse(
                row.id(), row.username(), row.displayName(), row.email(), row.avatar(), row.status(),
                row.maxSessions(), roleIds, roleCodes, row.lastLoginAt(), row.createdAt(), row.updatedAt()
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

    private UserSecurityState requireUserSecurityStateForUpdate(Long userId) {
        return jdbcClient.sql("""
                        SELECT status, max_sessions
                        FROM admin_user
                        WHERE id = :userId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new UserSecurityState(
                        rs.getString("status"),
                        rs.getInt("max_sessions")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));
    }

    private void publishSessionPolicyChange(
            Long userId,
            boolean revokeAll,
            boolean sessionPolicyChanged,
            int maxSessions
    ) {
        if (revokeAll || sessionPolicyChanged) {
            eventPublisher.publishEvent(new AdminSessionPolicyChangedEvent(userId, revokeAll, maxSessions));
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

    private void requireGeneralCreateRoleSelection(List<Long> roleIds) {
        Set<String> requestedRoleCodes = roleCodes(roleIds);
        requireGuestRoleExclusive(requestedRoleCodes);
        if (requestedRoleCodes.stream().anyMatch(CUSTOMER_SERVICE_ROLE_CODES::contains)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
    }

    private void requireGeneralUpdateRoleSelection(Long userId, List<Long> roleIds) {
        Set<String> requestedRoleCodes = roleCodes(roleIds);
        requireGuestRoleExclusive(requestedRoleCodes);
        Set<String> requestedCustomerServiceRoles = new HashSet<>(requestedRoleCodes);
        requestedCustomerServiceRoles.retainAll(CUSTOMER_SERVICE_ROLE_CODES);
        Set<String> currentCustomerServiceRoles = new HashSet<>(jdbcClient.sql("""
                        SELECT role_item.code
                        FROM admin_user_role user_role
                        JOIN admin_role role_item ON role_item.id = user_role.role_id
                        WHERE user_role.user_id = :userId
                          AND role_item.code IN ('R_CUSTOMER_SERVICE', 'R_CUSTOMER_SERVICE_MANAGER')
                        """)
                .param("userId", userId)
                .query(String.class)
                .list());
        if (!requestedCustomerServiceRoles.equals(currentCustomerServiceRoles)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
    }

    private Set<String> roleCodes(List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(namedParameterJdbcTemplate.queryForList(
                "SELECT code FROM admin_role WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", roleIds),
                String.class
        ));
    }

    private void requireGuestRoleExclusive(Set<String> roleCodes) {
        if (roleCodes.contains("R_GUEST") && roleCodes.size() != 1) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
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
                rs.getInt("max_sessions"),
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

    private int normalizeMaxSessions(Integer requested, int fallback) {
        return requested == null ? fallback : requested;
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
            int maxSessions,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record UserRoleRow(Long userId, Long roleId, String roleCode) {
    }

    private record UserSecurityState(String status, int maxSessions) {
    }
}
