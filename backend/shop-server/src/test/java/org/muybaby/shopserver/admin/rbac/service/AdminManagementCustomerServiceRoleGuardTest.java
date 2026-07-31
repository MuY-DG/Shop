package org.muybaby.shopserver.admin.rbac.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserCreateRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserUpdateRequest;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminManagementCustomerServiceRoleGuardTest {

    private static final long AGENT_USER_ID = 9_520_001L;

    @Autowired
    private AdminManagementService adminManagementService;

    @Autowired
    private JdbcClient jdbcClient;

    private Long agentRoleId;
    private Long managerRoleId;
    private Long guestRoleId;

    @BeforeEach
    void setUp() {
        agentRoleId = roleId("R_CUSTOMER_SERVICE");
        managerRoleId = roleId("R_CUSTOMER_SERVICE_MANAGER");
        guestRoleId = roleId("R_GUEST");
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, avatar, status)
                        values
                            (:userId, 'guarded-agent', :passwordHash, '客服',
                             'guarded-agent@shop.local', '', 'ENABLED')
                        """)
                .param("userId", AGENT_USER_ID)
                .param("passwordHash", passwordHash)
                .update();
        jdbcClient.sql("insert into admin_user_role (user_id, role_id) values (:userId, :roleId)")
                .param("userId", AGENT_USER_ID)
                .param("roleId", agentRoleId)
                .update();
    }

    @Test
    void generalCreateCannotAssignCustomerServiceRolesOrMixGuestRole() {
        assertPermissionDenied(() -> adminManagementService.createUser(new AdminUserCreateRequest(
                "created-agent", "客服", "created-agent@shop.local", "123456", "",
                List.of(agentRoleId)
        )));

        assertPermissionDenied(() -> adminManagementService.createUser(new AdminUserCreateRequest(
                "mixed-guest", "游客", "mixed-guest@shop.local", "123456", "",
                List.of(guestRoleId, roleId("R_ADMIN"))
        )));

        Long guestUserId = adminManagementService.createUser(new AdminUserCreateRequest(
                "sole-guest", "游客", "sole-guest@shop.local", "123456", "",
                List.of(guestRoleId)
        ));
        assertThat(jdbcClient.sql("""
                        select role_item.code
                        from admin_user_role user_role
                        join admin_role role_item on role_item.id = user_role.role_id
                        where user_role.user_id = :userId
                        """)
                .param("userId", guestUserId)
                .query(String.class)
                .list()).containsExactly("R_GUEST");
    }

    @Test
    void generalUpdateMayKeepButCannotChangeCustomerServiceRoles() {
        adminManagementService.updateUser(1L, AGENT_USER_ID, updateRequest(List.of(agentRoleId)));
        assertThat(jdbcClient.sql("select display_name from admin_user where id = :userId")
                .param("userId", AGENT_USER_ID)
                .query(String.class)
                .single()).isEqualTo("新客服名");

        assertPermissionDenied(() -> adminManagementService.updateUser(
                1L, AGENT_USER_ID, updateRequest(List.of(roleId("R_ADMIN")))
        ));
        assertPermissionDenied(() -> adminManagementService.updateUser(
                1L, AGENT_USER_ID, updateRequest(List.of(agentRoleId, managerRoleId))
        ));
    }

    private AdminUserUpdateRequest updateRequest(List<Long> roleIds) {
        return new AdminUserUpdateRequest(
                "新客服名", "guarded-agent@shop.local", "", "", "ENABLED", roleIds
        );
    }

    private Long roleId(String code) {
        return jdbcClient.sql("select id from admin_role where code = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private void assertPermissionDenied(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.PERMISSION_DENIED));
    }
}
