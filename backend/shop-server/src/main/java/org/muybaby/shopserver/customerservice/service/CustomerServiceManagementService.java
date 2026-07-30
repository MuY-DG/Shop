package org.muybaby.shopserver.customerservice.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserUpdateRequest;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomerServiceManagementService {

    private static final String AGENT_ROLE = "R_CUSTOMER_SERVICE";
    private static final String MANAGER_ROLE = "R_CUSTOMER_SERVICE_MANAGER";

    private final JdbcClient jdbcClient;
    private final RealtimeSessionHub realtimeSessionHub;

    public CustomerServiceManagementService(
            JdbcClient jdbcClient,
            RealtimeSessionHub realtimeSessionHub
    ) {
        this.jdbcClient = jdbcClient;
        this.realtimeSessionHub = realtimeSessionHub;
    }

    public List<ManagedUserResponse> users(String keyword) {
        String normalizedKeyword = normalize(keyword);
        String keywordPattern = "%" + normalizedKeyword + "%";
        return jdbcClient.sql("""
                        SELECT admin.id,
                               admin.username,
                               admin.display_name,
                               admin.avatar AS admin_avatar,
                               admin.status,
                               CASE WHEN agent_role.user_id IS NULL THEN FALSE ELSE TRUE END AS is_agent,
                               CASE WHEN manager_role.user_id IS NULL THEN FALSE ELSE TRUE END AS is_manager,
                               profile.service_name_override,
                               config.default_service_name,
                               config.avatar AS service_avatar,
                               COALESCE(agent_state.work_status, 'OFFLINE') AS work_status,
                               COALESCE(active_count.active_count, 0) AS active_count,
                               COALESCE(agent_state.max_active_conversations, 5) AS max_active_conversations,
                               COALESCE(profile.routing_weight, 100) AS routing_weight,
                               COALESCE(profile.updated_at, agent_state.updated_at, admin.updated_at) AS managed_updated_at
                        FROM admin_user admin
                        CROSS JOIN customer_service_config config
                        LEFT JOIN (
                            SELECT user_role.user_id
                            FROM admin_user_role user_role
                            JOIN admin_role role_item ON role_item.id = user_role.role_id
                            WHERE role_item.code = 'R_CUSTOMER_SERVICE'
                        ) agent_role ON agent_role.user_id = admin.id
                        LEFT JOIN (
                            SELECT user_role.user_id
                            FROM admin_user_role user_role
                            JOIN admin_role role_item ON role_item.id = user_role.role_id
                            WHERE role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                        ) manager_role ON manager_role.user_id = admin.id
                        LEFT JOIN customer_service_agent_profile profile
                               ON profile.admin_user_id = admin.id
                        LEFT JOIN customer_service_agent_state agent_state
                               ON agent_state.admin_user_id = admin.id
                        LEFT JOIN (
                            SELECT assigned_admin_user_id, COUNT(*) AS active_count
                            FROM customer_service_conversation
                            WHERE status = 'ACTIVE'
                            GROUP BY assigned_admin_user_id
                        ) active_count ON active_count.assigned_admin_user_id = admin.id
                        WHERE admin.status = 'ENABLED'
                          AND (
                              :keyword = ''
                              OR LOWER(admin.username) LIKE LOWER(:keywordPattern)
                              OR LOWER(admin.display_name) LIKE LOWER(:keywordPattern)
                              OR LOWER(COALESCE(profile.service_name_override, '')) LIKE LOWER(:keywordPattern)
                          )
                        ORDER BY
                            CASE
                                WHEN agent_role.user_id IS NOT NULL OR manager_role.user_id IS NOT NULL
                                THEN 0 ELSE 1
                            END,
                            admin.id
                        """)
                .param("keyword", normalizedKeyword)
                .param("keywordPattern", keywordPattern)
                .query(this::mapManagedUser)
                .list();
    }

    public CustomerServiceConfigResponse config() {
        return jdbcClient.sql("""
                        SELECT default_service_name, avatar, auto_assign_enabled,
                               assignment_strategy, sticky_agent_enabled, sticky_window_hours,
                               updated_by, updated_at
                        FROM customer_service_config
                        WHERE id = 1
                        """)
                .query(this::mapConfig)
                .single();
    }

    @Transactional
    public ManagedUserResponse updateUser(
            Long operatorUserId,
            Long adminUserId,
            ManagedUserUpdateRequest request
    ) {
        requireEnabledAdmin(adminUserId);
        boolean wasAgent = hasRole(adminUserId, AGENT_ROLE);
        boolean wasManager = hasRole(adminUserId, MANAGER_ROLE);
        boolean agent = Boolean.TRUE.equals(request.agent());
        boolean manager = Boolean.TRUE.equals(request.manager());

        if (wasManager != manager && !hasRole(operatorUserId, "R_SUPER")) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        if (wasAgent && !agent && activeConversationCount(adminUserId) > 0) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_AGENT_HAS_ACTIVE_CONVERSATIONS);
        }

        setRole(adminUserId, AGENT_ROLE, agent);
        setRole(adminUserId, MANAGER_ROLE, manager);

        if (agent) {
            LocalDateTime now = LocalDateTime.now();
            jdbcClient.sql("""
                            INSERT INTO customer_service_agent_state
                                (admin_user_id, work_status, max_active_conversations, updated_at)
                            SELECT :adminUserId, 'OFFLINE', :maxActiveConversations, :now
                            WHERE NOT EXISTS (
                                SELECT 1
                                FROM customer_service_agent_state
                                WHERE admin_user_id = :adminUserId
                            )
                            """)
                    .param("adminUserId", adminUserId)
                    .param("maxActiveConversations", request.maxActiveConversations())
                    .param("now", now)
                    .update();
            jdbcClient.sql("""
                            UPDATE customer_service_agent_state
                            SET max_active_conversations = :maxActiveConversations,
                                updated_at = :now
                            WHERE admin_user_id = :adminUserId
                            """)
                    .param("adminUserId", adminUserId)
                    .param("maxActiveConversations", request.maxActiveConversations())
                    .param("now", now)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO customer_service_agent_profile
                                (admin_user_id, service_name_override, routing_weight, updated_by, updated_at)
                            SELECT :adminUserId, :serviceNameOverride, :routingWeight, :operatorUserId, :now
                            WHERE NOT EXISTS (
                                SELECT 1
                                FROM customer_service_agent_profile
                                WHERE admin_user_id = :adminUserId
                            )
                            """)
                    .param("adminUserId", adminUserId)
                    .param("serviceNameOverride", nullableTrimmed(request.serviceNameOverride()))
                    .param("routingWeight", request.routingWeight())
                    .param("operatorUserId", operatorUserId)
                    .param("now", now)
                    .update();
            jdbcClient.sql("""
                            UPDATE customer_service_agent_profile
                            SET service_name_override = :serviceNameOverride,
                                routing_weight = :routingWeight,
                                updated_by = :operatorUserId,
                                updated_at = :now
                            WHERE admin_user_id = :adminUserId
                            """)
                    .param("adminUserId", adminUserId)
                    .param("serviceNameOverride", nullableTrimmed(request.serviceNameOverride()))
                    .param("routingWeight", request.routingWeight())
                    .param("operatorUserId", operatorUserId)
                    .param("now", now)
                    .update();
        }

        return requireManagedUser(adminUserId);
    }

    @Transactional
    public CustomerServiceConfigResponse updateConfig(
            Long operatorUserId,
            CustomerServiceConfigUpdateRequest request
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE customer_service_config
                        SET default_service_name = :defaultServiceName,
                            avatar = :avatar,
                            auto_assign_enabled = :autoAssignEnabled,
                            assignment_strategy = :assignmentStrategy,
                            sticky_agent_enabled = :stickyAgentEnabled,
                            sticky_window_hours = :stickyWindowHours,
                            updated_by = :operatorUserId,
                            updated_at = :now
                        WHERE id = 1
                        """)
                .param("defaultServiceName", request.defaultServiceName().trim())
                .param("avatar", normalize(request.avatar()))
                .param("autoAssignEnabled", request.autoAssignEnabled())
                .param("assignmentStrategy", request.assignmentStrategy())
                .param("stickyAgentEnabled", request.stickyAgentEnabled())
                .param("stickyWindowHours", request.stickyWindowHours())
                .param("operatorUserId", operatorUserId)
                .param("now", LocalDateTime.now())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CUSTOMER_SERVICE_CONFIG_INVALID);
        }
        return config();
    }

    private ManagedUserResponse requireManagedUser(Long adminUserId) {
        return users("").stream()
                .filter(user -> adminUserId.equals(user.adminUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));
    }

    private ManagedUserResponse mapManagedUser(ResultSet rs, int rowNum) throws SQLException {
        Long adminUserId = rs.getLong("id");
        boolean agent = rs.getBoolean("is_agent");
        String nameOverride = rs.getString("service_name_override");
        String defaultName = rs.getString("default_service_name");
        return new ManagedUserResponse(
                adminUserId,
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("admin_avatar"),
                rs.getString("status"),
                agent,
                rs.getBoolean("is_manager"),
                StringUtils.hasText(nameOverride) ? nameOverride : defaultName,
                nameOverride,
                rs.getString("service_avatar"),
                agent && realtimeSessionHub.isAdminOnline(adminUserId),
                rs.getString("work_status"),
                rs.getInt("active_count"),
                rs.getInt("max_active_conversations"),
                rs.getInt("routing_weight"),
                rs.getTimestamp("managed_updated_at").toLocalDateTime()
        );
    }

    private CustomerServiceConfigResponse mapConfig(ResultSet rs, int rowNum) throws SQLException {
        long updatedBy = rs.getLong("updated_by");
        return new CustomerServiceConfigResponse(
                rs.getString("default_service_name"),
                rs.getString("avatar"),
                rs.getBoolean("auto_assign_enabled"),
                rs.getString("assignment_strategy"),
                rs.getBoolean("sticky_agent_enabled"),
                rs.getInt("sticky_window_hours"),
                rs.wasNull() ? null : updatedBy,
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private void requireEnabledAdmin(Long adminUserId) {
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM admin_user
                        WHERE id = :adminUserId AND status = 'ENABLED'
                        """)
                .param("adminUserId", adminUserId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
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
}
