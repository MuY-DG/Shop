package org.muybaby.shopserver.admin.rbac.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserUpdateRequest;
import org.muybaby.shopserver.auth.session.AdminSessionPolicyChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
@Transactional
class AdminManagementSessionPolicyTest {

    private static final long USER_ID = 9_510_001L;

    @Autowired
    private AdminManagementService adminManagementService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ApplicationEvents applicationEvents;

    @BeforeEach
    void insertAdminUser() {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, avatar, status, max_sessions)
                        values
                            (:userId, 'SessionPolicyAdmin', :passwordHash, 'Session Policy Admin',
                             'session-policy-admin@shop.local', '', 'ENABLED', 3)
                        """)
                .param("userId", USER_ID)
                .param("passwordHash", passwordHash)
                .update();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        values (:userId, 1)
                        """)
                .param("userId", USER_ID)
                .update();
    }

    @Test
    void publishesOnlyMeaningfulPolicyChangesAndIncrementsSecurityVersion() {
        adminManagementService.updateUser(1L, USER_ID, request("", 3));

        assertThat(events()).isEmpty();
        assertThat(authVersion()).isZero();

        adminManagementService.updateUser(1L, USER_ID, request("", 2));

        assertThat(events()).containsExactly(new AdminSessionPolicyChangedEvent(USER_ID, false, 2));
        assertThat(authVersion()).isZero();

        adminManagementService.updateUser(1L, USER_ID, request("654321", 2));

        assertThat(events()).containsExactly(
                new AdminSessionPolicyChangedEvent(USER_ID, false, 2),
                new AdminSessionPolicyChangedEvent(USER_ID, true, 2)
        );
        assertThat(authVersion()).isEqualTo(1L);

        adminManagementService.disableUser(1L, USER_ID);

        assertThat(events()).containsExactly(
                new AdminSessionPolicyChangedEvent(USER_ID, false, 2),
                new AdminSessionPolicyChangedEvent(USER_ID, true, 2),
                new AdminSessionPolicyChangedEvent(USER_ID, true, 2)
        );
        assertThat(authVersion()).isEqualTo(2L);
    }

    private AdminUserUpdateRequest request(String password, int maxSessions) {
        return new AdminUserUpdateRequest(
                "Session Policy Admin",
                "session-policy-admin@shop.local",
                password,
                "",
                "ENABLED",
                List.of(1L),
                maxSessions
        );
    }

    private List<AdminSessionPolicyChangedEvent> events() {
        return applicationEvents.stream(AdminSessionPolicyChangedEvent.class)
                .filter(event -> event.userId().equals(USER_ID))
                .toList();
    }

    private long authVersion() {
        return jdbcClient.sql("select auth_version from admin_user where id = :userId")
                .param("userId", USER_ID)
                .query(Long.class)
                .single();
    }
}
