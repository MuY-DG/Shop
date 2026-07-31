package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceIdentityUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceRoutingUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.GuestUserResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserCreateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserManagerUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserNameUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.RoutingAgentResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.RoutingAgentUpdateRequest;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CustomerServiceManagementService {

    private static final long CONFIG_ID = 1L;
    private static final String AGENT_ROLE = "R_CUSTOMER_SERVICE";
    private static final String MANAGER_ROLE = "R_CUSTOMER_SERVICE_MANAGER";
    private static final String GUEST_ROLE = "R_GUEST";

    private final JdbcClient jdbcClient;
    private final RealtimeSessionHub realtimeSessionHub;
    private final StorageUsageService storageUsageService;

    public CustomerServiceManagementService(
            JdbcClient jdbcClient,
            RealtimeSessionHub realtimeSessionHub,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.realtimeSessionHub = realtimeSessionHub;
        this.storageUsageService = storageUsageService;
    }

    public List<ManagedUserResponse> users(String keyword) {
        String normalizedKeyword = normalize(keyword);
        String keywordPattern = "%" + normalizedKeyword + "%";
        return jdbcClient.sql("""
                        SELECT admin.id,
                               admin.username,
                               COALESCE(NULLIF(profile.service_name_override, ''),
                                        config.default_service_name) AS service_name,
                               config.avatar AS service_avatar,
                               CASE WHEN manager_role.user_id IS NULL THEN FALSE ELSE TRUE END AS is_manager,
                               COALESCE(profile.bound_at, admin.created_at) AS bound_at
                        FROM admin_user admin
                        CROSS JOIN customer_service_config config
                        JOIN admin_user_role agent_user_role
                          ON agent_user_role.user_id = admin.id
                        JOIN admin_role agent_role
                          ON agent_role.id = agent_user_role.role_id
                         AND agent_role.code = 'R_CUSTOMER_SERVICE'
                         AND agent_role.enabled = TRUE
                        LEFT JOIN customer_service_agent_profile profile
                          ON profile.admin_user_id = admin.id
                        LEFT JOIN (
                            SELECT user_role.user_id
                            FROM admin_user_role user_role
                            JOIN admin_role role_item ON role_item.id = user_role.role_id
                            WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                              AND role_item.enabled = TRUE
                        ) manager_role ON manager_role.user_id = admin.id
                        WHERE admin.status = 'ENABLED'
                          AND (
                              :keyword = ''
                              OR LOWER(admin.username) LIKE LOWER(:keywordPattern)
                              OR LOWER(COALESCE(profile.service_name_override, ''))
                                   LIKE LOWER(:keywordPattern)
                              OR LOWER(config.default_service_name) LIKE LOWER(:keywordPattern)
                          )
                        ORDER BY COALESCE(profile.bound_at, admin.created_at), admin.id
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", keywordPattern)
                .query(this::mapManagedUser)
                .list();
    }

    public List<GuestUserResponse> guests(String keyword) {
        String normalizedKeyword = normalize(keyword);
        String keywordPattern = "%" + normalizedKeyword + "%";
        return jdbcClient.sql("""
                        SELECT admin.id, admin.username, admin.display_name, admin.avatar
                        FROM admin_user admin
                        WHERE admin.status = 'ENABLED'
                          AND EXISTS (
                              SELECT 1
                              FROM admin_user_role user_role
                              JOIN admin_role role_item ON role_item.id = user_role.role_id
                              WHERE user_role.user_id = admin.id
                                AND role_item.code = 'R_GUEST'
                                AND role_item.enabled = TRUE
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM admin_user_role user_role
                              JOIN admin_role role_item ON role_item.id = user_role.role_id
                              WHERE user_role.user_id = admin.id
                                AND role_item.code <> 'R_GUEST'
                          )
                          AND (
                              :keyword = ''
                              OR LOWER(admin.username) LIKE LOWER(:keywordPattern)
                              OR LOWER(admin.display_name) LIKE LOWER(:keywordPattern)
                          )
                        ORDER BY admin.id
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", keywordPattern)
                .query((rs, rowNum) -> new GuestUserResponse(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("avatar")
                ))
                .list();
    }

    @Transactional
    public ManagedUserResponse addUser(
            Long operatorUserId,
            Long adminUserId,
            ManagedUserCreateRequest request
    ) {
        requireEnabledAdminForUpdate(adminUserId);
        Set<String> roles = roleCodes(adminUserId);
        if (!roles.equals(Set.of(GUEST_ROLE))) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }

        setRole(adminUserId, GUEST_ROLE, false);
        setRole(adminUserId, AGENT_ROLE, true);
        LocalDateTime now = LocalDateTime.now();
        String serviceName = nullableTrimmed(request.serviceName());
        int inserted = jdbcClient.sql("""
                        INSERT INTO customer_service_agent_profile
                            (admin_user_id, service_name_override, routing_weight,
                             auto_accept_enabled, auto_accept_below, auto_accept_count,
                             bound_at, updated_by, updated_at)
                        SELECT :adminUserId, :serviceName, 100,
                               FALSE, 5, 1, :now, :operatorUserId, :now
                        WHERE NOT EXISTS (
                            SELECT 1 FROM customer_service_agent_profile
                            WHERE admin_user_id = :adminUserId
                        )
                        """)
                .param("adminUserId", adminUserId)
                .param("serviceName", serviceName)
                .param("operatorUserId", operatorUserId)
                .param("now", now)
                .update();
        if (inserted == 0) {
            jdbcClient.sql("""
                            UPDATE customer_service_agent_profile
                            SET service_name_override = :serviceName,
                                auto_accept_enabled = FALSE,
                                auto_accept_below = 5,
                                auto_accept_count = 1,
                                bound_at = :now,
                                updated_by = :operatorUserId,
                                updated_at = :now
                            WHERE admin_user_id = :adminUserId
                            """)
                    .param("serviceName", serviceName)
                    .param("now", now)
                    .param("operatorUserId", operatorUserId)
                    .param("adminUserId", adminUserId)
                    .update();
        }
        ensureAgentState(adminUserId, now);
        jdbcClient.sql("""
                        UPDATE customer_service_agent_state
                        SET work_status = 'OFFLINE',
                            max_active_conversations = NULL,
                            updated_at = :now
                        WHERE admin_user_id = :adminUserId
                        """)
                .param("now", now)
                .param("adminUserId", adminUserId)
                .update();
        bumpAuthorization(adminUserId, now);
        realtimeSessionHub.disconnectAdmin(adminUserId);
        return requireManagedUser(adminUserId);
    }

    @Transactional
    public ManagedUserResponse updateName(
            Long operatorUserId,
            Long adminUserId,
            ManagedUserNameUpdateRequest request
    ) {
        requireAgentForUpdate(adminUserId);
        int updated = jdbcClient.sql("""
                        UPDATE customer_service_agent_profile
                        SET service_name_override = :serviceName,
                            updated_by = :operatorUserId,
                            updated_at = :now
                        WHERE admin_user_id = :adminUserId
                        """)
                .param("serviceName", request.serviceName().trim())
                .param("operatorUserId", operatorUserId)
                .param("now", LocalDateTime.now())
                .param("adminUserId", adminUserId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        return requireManagedUser(adminUserId);
    }

    @Transactional
    public ManagedUserResponse updateManager(
            Long operatorUserId,
            Long adminUserId,
            ManagedUserManagerUpdateRequest request
    ) {
        requireAgentForUpdate(adminUserId);
        boolean requested = Boolean.TRUE.equals(request.manager());
        boolean current = hasRole(adminUserId, MANAGER_ROLE);
        if (requested != current) {
            setRole(adminUserId, MANAGER_ROLE, requested);
            LocalDateTime now = LocalDateTime.now();
            jdbcClient.sql("""
                            UPDATE customer_service_agent_profile
                            SET updated_by = :operatorUserId, updated_at = :now
                            WHERE admin_user_id = :adminUserId
                            """)
                    .param("operatorUserId", operatorUserId)
                    .param("now", now)
                    .param("adminUserId", adminUserId)
                    .update();
            bumpAuthorization(adminUserId, now);
            realtimeSessionHub.disconnectAdmin(adminUserId);
        }
        return requireManagedUser(adminUserId);
    }

    @Transactional
    public void deleteUser(Long operatorUserId, Long adminUserId) {
        if (adminUserId.equals(operatorUserId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        requireAgentForUpdate(adminUserId);
        Set<String> roles = roleCodes(adminUserId);
        if (!roles.contains(AGENT_ROLE) || roles.contains("R_SUPER")) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        if (activeConversationCount(adminUserId) > 0) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_AGENT_HAS_ACTIVE_CONVERSATIONS);
        }

        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        UPDATE customer_service_transfer_request
                        SET status = 'CANCELLED', pending_key = NULL,
                            resolved_at = :now, updated_at = :now
                        WHERE status = 'PENDING'
                          AND (from_admin_user_id = :adminUserId
                               OR to_admin_user_id = :adminUserId)
                        """)
                .param("now", now)
                .param("adminUserId", adminUserId)
                .update();
        jdbcClient.sql("DELETE FROM admin_user_role WHERE user_id = :adminUserId")
                .param("adminUserId", adminUserId)
                .update();
        setRole(adminUserId, GUEST_ROLE, true);
        jdbcClient.sql("""
                        UPDATE customer_service_agent_state
                        SET work_status = 'OFFLINE',
                            max_active_conversations = NULL,
                            updated_at = :now
                        WHERE admin_user_id = :adminUserId
                        """)
                .param("now", now)
                .param("adminUserId", adminUserId)
                .update();
        jdbcClient.sql("""
                        UPDATE customer_service_agent_profile
                        SET auto_accept_enabled = FALSE,
                            updated_by = :operatorUserId,
                            updated_at = :now
                        WHERE admin_user_id = :adminUserId
                        """)
                .param("operatorUserId", operatorUserId)
                .param("now", now)
                .param("adminUserId", adminUserId)
                .update();
        bumpAuthorization(adminUserId, now);
        realtimeSessionHub.disconnectAdmin(adminUserId);
    }

    public CustomerServiceConfigResponse config() {
        ConfigRow config = configRow();
        List<RoutingAgentRow> agentRows = routingAgentRows();
        int totalWeight = "WEIGHTED".equals(config.assignmentStrategy())
                ? agentRows.stream()
                        .map(RoutingAgentRow::maxActiveConversations)
                        .filter(java.util.Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .sum()
                : 0;
        List<RoutingAgentResponse> agents = agentRows.stream()
                .map(row -> {
                    int weight = "WEIGHTED".equals(config.assignmentStrategy())
                            && row.maxActiveConversations() != null
                            ? row.maxActiveConversations()
                            : 0;
                    double percent = totalWeight == 0
                            ? 0D
                            : Math.round(weight * 10_000D / totalWeight) / 100D;
                    return new RoutingAgentResponse(
                            row.adminUserId(),
                            row.username(),
                            row.serviceName(),
                            realtimeSessionHub.isAdminOnline(row.adminUserId()),
                            row.maxActiveConversations(),
                            weight,
                            percent
                    );
                })
                .toList();
        return new CustomerServiceConfigResponse(
                config.defaultServiceName(),
                config.avatar(),
                config.avatarFileId(),
                config.assignmentStrategy(),
                config.stickyAgentEnabled(),
                config.stickyWindowHours(),
                agents
        );
    }

    @Transactional
    public CustomerServiceConfigResponse updateRouting(
            Long operatorUserId,
            CustomerServiceRoutingUpdateRequest request
    ) {
        Map<Long, RoutingAgentUpdateRequest> requestedAgents = new LinkedHashMap<>();
        for (RoutingAgentUpdateRequest agent : request.agents()) {
            if (requestedAgents.putIfAbsent(agent.adminUserId(), agent) != null) {
                throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
            }
        }
        Set<Long> currentAgentIds = new HashSet<>(currentAgentIds());
        if (!currentAgentIds.containsAll(requestedAgents.keySet())) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
        }
        if ("WEIGHTED".equals(request.assignmentStrategy())) {
            if (!requestedAgents.keySet().equals(currentAgentIds)
                    || requestedAgents.values().stream()
                            .anyMatch(agent -> agent.maxActiveConversations() == null)) {
                throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        for (RoutingAgentUpdateRequest agent : requestedAgents.values()) {
            ensureAgentState(agent.adminUserId(), now);
            jdbcClient.sql("""
                            UPDATE customer_service_agent_state
                            SET max_active_conversations = :maxActiveConversations,
                                updated_at = :now
                            WHERE admin_user_id = :adminUserId
                            """)
                    .param("maxActiveConversations", agent.maxActiveConversations())
                    .param("now", now)
                    .param("adminUserId", agent.adminUserId())
                    .update();
        }
        int updated = jdbcClient.sql("""
                        UPDATE customer_service_config
                        SET auto_assign_enabled = TRUE,
                            assignment_strategy = :assignmentStrategy,
                            sticky_agent_enabled = :stickyAgentEnabled,
                            sticky_window_hours = :stickyWindowHours,
                            updated_by = :operatorUserId,
                            updated_at = :now
                        WHERE id = 1
                        """)
                .param("assignmentStrategy", request.assignmentStrategy())
                .param("stickyAgentEnabled", request.stickyAgentEnabled())
                .param("stickyWindowHours", request.stickyWindowHours())
                .param("operatorUserId", operatorUserId)
                .param("now", now)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
        }
        return config();
    }

    @Transactional
    public CustomerServiceConfigResponse updateIdentity(
            Long operatorUserId,
            CustomerServiceIdentityUpdateRequest request
    ) {
        Long avatarFileId = request.avatarFileId();
        String avatar = "";
        List<StorageUsageService.UsageAssignment> usages = List.of();
        if (avatarFileId != null) {
            storageUsageService.requireActivePublicMedia(avatarFileId, StorageMediaKind.IMAGE);
            avatar = jdbcClient.sql("""
                            SELECT public_url
                            FROM storage_asset
                            WHERE id = :avatarFileId
                              AND scope = 'LIBRARY'
                              AND media_kind = 'IMAGE'
                              AND visibility = 'PUBLIC'
                              AND status = 'ACTIVE'
                            """)
                    .param("avatarFileId", avatarFileId)
                    .query(String.class)
                    .optional()
                    .filter(StringUtils::hasText)
                    .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
            usages = List.of(new StorageUsageService.UsageAssignment(
                    avatarFileId,
                    StorageFileUsageType.CUSTOMER_SERVICE_AVATAR,
                    avatar,
                    0,
                    true
            ));
        }

        int updated = jdbcClient.sql("""
                        UPDATE customer_service_config
                        SET default_service_name = :defaultServiceName,
                            avatar = :avatar,
                            avatar_file_id = :avatarFileId,
                            updated_by = :operatorUserId,
                            updated_at = :now
                        WHERE id = 1
                        """)
                .param("defaultServiceName", request.defaultServiceName().trim())
                .param("avatar", avatar)
                .param("avatarFileId", avatarFileId)
                .param("operatorUserId", operatorUserId)
                .param("now", LocalDateTime.now())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
        }
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.CUSTOMER_SERVICE_CONFIG,
                CONFIG_ID,
                "客服统一头像",
                usages
        );
        return config();
    }

    private ConfigRow configRow() {
        return jdbcClient.sql("""
                        SELECT default_service_name, avatar, avatar_file_id,
                               assignment_strategy, sticky_agent_enabled, sticky_window_hours
                        FROM customer_service_config
                        WHERE id = 1
                        """)
                .query((rs, rowNum) -> new ConfigRow(
                        rs.getString("default_service_name"),
                        rs.getString("avatar"),
                        rs.getObject("avatar_file_id", Long.class),
                        rs.getString("assignment_strategy"),
                        rs.getBoolean("sticky_agent_enabled"),
                        rs.getInt("sticky_window_hours")
                ))
                .single();
    }

    private List<RoutingAgentRow> routingAgentRows() {
        return jdbcClient.sql("""
                        SELECT admin.id, admin.username,
                               COALESCE(NULLIF(profile.service_name_override, ''),
                                        config.default_service_name) AS service_name,
                               state.max_active_conversations
                        FROM admin_user admin
                        CROSS JOIN customer_service_config config
                        JOIN admin_user_role user_role ON user_role.user_id = admin.id
                        JOIN admin_role role_item ON role_item.id = user_role.role_id
                        LEFT JOIN customer_service_agent_profile profile
                          ON profile.admin_user_id = admin.id
                        LEFT JOIN customer_service_agent_state state
                          ON state.admin_user_id = admin.id
                        WHERE admin.status = 'ENABLED'
                          AND role_item.code = 'R_CUSTOMER_SERVICE'
                          AND role_item.enabled = TRUE
                        ORDER BY admin.id
                        """)
                .query((rs, rowNum) -> new RoutingAgentRow(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("service_name"),
                        rs.getObject("max_active_conversations", Integer.class)
                ))
                .list();
    }

    private List<Long> currentAgentIds() {
        return jdbcClient.sql("""
                        SELECT DISTINCT user_role.user_id
                        FROM admin_user_role user_role
                        JOIN admin_role role_item ON role_item.id = user_role.role_id
                        JOIN admin_user admin ON admin.id = user_role.user_id
                        WHERE role_item.code = 'R_CUSTOMER_SERVICE'
                          AND role_item.enabled = TRUE
                          AND admin.status = 'ENABLED'
                        ORDER BY user_role.user_id
                        """)
                .query(Long.class)
                .list();
    }

    private ManagedUserResponse requireManagedUser(Long adminUserId) {
        return users("").stream()
                .filter(user -> adminUserId.equals(user.adminUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));
    }

    private ManagedUserResponse mapManagedUser(ResultSet rs, int rowNum) throws SQLException {
        Long adminUserId = rs.getLong("id");
        return new ManagedUserResponse(
                adminUserId,
                rs.getString("username"),
                rs.getString("service_name"),
                rs.getString("service_avatar"),
                realtimeSessionHub.isAdminOnline(adminUserId),
                rs.getBoolean("is_manager"),
                rs.getTimestamp("bound_at").toLocalDateTime()
        );
    }

    private void requireEnabledAdminForUpdate(Long adminUserId) {
        jdbcClient.sql("""
                        SELECT id
                        FROM admin_user
                        WHERE id = :adminUserId AND status = 'ENABLED'
                        FOR UPDATE
                        """)
                .param("adminUserId", adminUserId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));
    }

    private void requireAgentForUpdate(Long adminUserId) {
        requireEnabledAdminForUpdate(adminUserId);
        if (!hasRole(adminUserId, AGENT_ROLE)) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    private Set<String> roleCodes(Long adminUserId) {
        return new HashSet<>(jdbcClient.sql("""
                        SELECT role_item.code
                        FROM admin_user_role user_role
                        JOIN admin_role role_item ON role_item.id = user_role.role_id
                        WHERE user_role.user_id = :adminUserId
                        """)
                .param("adminUserId", adminUserId)
                .query(String.class)
                .list());
    }

    private boolean hasRole(Long adminUserId, String roleCode) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM admin_user_role user_role
                        JOIN admin_role role_item ON role_item.id = user_role.role_id
                        WHERE user_role.user_id = :adminUserId
                          AND role_item.code = :roleCode
                          AND role_item.enabled = TRUE
                        """)
                .param("adminUserId", adminUserId)
                .param("roleCode", roleCode)
                .query(Long.class)
                .single() > 0;
    }

    private void setRole(Long adminUserId, String roleCode, boolean assigned) {
        Long roleId = jdbcClient.sql("""
                        SELECT id
                        FROM admin_role
                        WHERE code = :roleCode AND enabled = TRUE
                        """)
                .param("roleCode", roleCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_ROLE_UNAVAILABLE));
        if (assigned) {
            if (!hasRole(adminUserId, roleCode)) {
                jdbcClient.sql("""
                                INSERT INTO admin_user_role (user_id, role_id)
                                VALUES (:adminUserId, :roleId)
                                """)
                        .param("adminUserId", adminUserId)
                        .param("roleId", roleId)
                        .update();
            }
        } else {
            jdbcClient.sql("""
                            DELETE FROM admin_user_role
                            WHERE user_id = :adminUserId AND role_id = :roleId
                            """)
                    .param("adminUserId", adminUserId)
                    .param("roleId", roleId)
                    .update();
        }
    }

    private void ensureAgentState(Long adminUserId, LocalDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO customer_service_agent_state
                            (admin_user_id, work_status, max_active_conversations, updated_at)
                        SELECT :adminUserId, 'OFFLINE', NULL, :now
                        WHERE NOT EXISTS (
                            SELECT 1 FROM customer_service_agent_state
                            WHERE admin_user_id = :adminUserId
                        )
                        """)
                .param("adminUserId", adminUserId)
                .param("now", now)
                .update();
    }

    private void bumpAuthorization(Long adminUserId, LocalDateTime now) {
        int updated = jdbcClient.sql("""
                        UPDATE admin_user
                        SET auth_version = auth_version + 1,
                            updated_at = :now
                        WHERE id = :adminUserId AND status = 'ENABLED'
                        """)
                .param("now", now)
                .param("adminUserId", adminUserId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    private int activeConversationCount(Long adminUserId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM customer_service_conversation
                        WHERE assigned_admin_user_id = :adminUserId
                          AND status = 'ACTIVE'
                        """)
                .param("adminUserId", adminUserId)
                .query(Integer.class)
                .single();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullableTrimmed(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty() ? null : normalized;
    }

    private record ConfigRow(
            String defaultServiceName,
            String avatar,
            Long avatarFileId,
            String assignmentStrategy,
            boolean stickyAgentEnabled,
            int stickyWindowHours
    ) {
    }

    private record RoutingAgentRow(
            Long adminUserId,
            String username,
            String serviceName,
            Integer maxActiveConversations
    ) {
    }
}
