package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.PersonalSettingsUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.SendMessageRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceRoutingUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserCreateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.RoutingAgentUpdateRequest;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.customerservice.service.CustomerServiceManagementService;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.realtime.RealtimeConnectionPrincipal;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerServiceAutoAssignmentTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CustomerServiceService customerServiceService;

    @Autowired
    private CustomerServiceManagementService managementService;

    @Autowired
    private RealtimeSessionHub realtimeSessionHub;

    @Test
    void newConversationUsesConfiguredAgentNameAvatarAndAutomaticAssignment() {
        long adminUserId = insertAdmin();
        managementService.addUser(1L, adminUserId, new ManagedUserCreateRequest("小满"));
        managementService.updateRouting(1L, new CustomerServiceRoutingUpdateRequest(
                "LEAST_LOADED", true, 48, List.of()
        ));
        jdbcClient.sql("update customer_service_config set avatar = :avatar where id = 1")
                .param("avatar", "https://cdn.example.com/service.png")
                .update();

        AuthenticatedPrincipal adminPrincipal = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "service-agent",
                List.of("R_CUSTOMER_SERVICE"),
                List.of("customer-service:conversation:read", "customer-service:message:send")
        );
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("auto-assignment-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "service-agent",
                adminPrincipal.permissions()
        ));

        try {
            customerServiceService.updatePersonalSettings(
                    adminPrincipal,
                    new PersonalSettingsUpdateRequest("小满", true, 5, 1)
            );
            customerServiceService.updateAgentState(adminPrincipal, "AVAILABLE");
            long appUserId = insertAppUser();
            AuthenticatedPrincipal appPrincipal = new AuthenticatedPrincipal(
                    TokenKind.APP,
                    appUserId,
                    "app-user",
                    List.of(),
                    List.of()
            );
            customerServiceService.openForApp(appPrincipal, "GENERAL", null, null);
            customerServiceService.sendFromApp(
                    appPrincipal,
                    new SendMessageRequest("你好", "auto-assign-app-message")
            );

            Long assignedAdminUserId = jdbcClient.sql("""
                            select assigned_admin_user_id
                            from customer_service_conversation
                            where app_user_id = :appUserId
                              and status = 'ACTIVE'
                            """)
                    .param("appUserId", appUserId)
                    .query(Long.class)
                    .single();
            assertThat(assignedAdminUserId).isEqualTo(adminUserId);

            long conversationId = jdbcClient.sql("""
                            select id
                            from customer_service_conversation
                            where app_user_id = :appUserId
                            """)
                    .param("appUserId", appUserId)
                    .query(Long.class)
                    .single();
            var reply = customerServiceService.sendFromAdmin(
                    adminPrincipal,
                    conversationId,
                    new SendMessageRequest("您好，请问有什么可以帮您？", "auto-assign-admin-message")
            );
            assertThat(reply.senderName()).isEqualTo("小满");
            assertThat(reply.senderAvatar()).isEqualTo("https://cdn.example.com/service.png");
        } finally {
            realtimeSessionHub.unregister(session);
        }
    }

    @Test
    void personalSettingsExposeUnifiedIdentityAndPreserveDefaultNameFallback() {
        long adminUserId = insertAdmin();
        managementService.addUser(1L, adminUserId, new ManagedUserCreateRequest(null));
        jdbcClient.sql("""
                        update customer_service_config
                        set default_service_name = '商城客服',
                            avatar = 'https://cdn.example.com/unified-service.png'
                        where id = 1
                        """)
                .update();
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "default-name-agent",
                List.of("R_CUSTOMER_SERVICE"),
                List.of("customer-service:settings:update")
        );

        var initial = customerServiceService.personalSettings(principal);
        assertThat(initial.serviceName()).isEqualTo("商城客服");
        assertThat(initial.serviceNameOverride()).isNull();
        assertThat(initial.defaultServiceName()).isEqualTo("商城客服");
        assertThat(initial.avatar()).isEqualTo("https://cdn.example.com/unified-service.png");

        var updated = customerServiceService.updatePersonalSettings(
                principal,
                new PersonalSettingsUpdateRequest(null, true, 6, 2)
        );
        assertThat(updated.serviceName()).isEqualTo("商城客服");
        assertThat(updated.serviceNameOverride()).isNull();

        jdbcClient.sql("""
                        update customer_service_config
                        set default_service_name = '新的默认客服'
                        where id = 1
                        """)
                .update();
        assertThat(customerServiceService.personalSettings(principal).serviceName())
                .isEqualTo("新的默认客服");
    }

    @Test
    void disabledPersonalAutoAcceptLeavesConversationWaiting() {
        AgentFixture agent = createAgent("disabled-agent", false, 5, 1);
        try {
            long conversationId = createWaitingConversation(880_011L, "disabled-auto-message");

            assertThat(conversationStatus(conversationId)).isEqualTo("WAITING");
            assertThat(assignedAdminUserId(conversationId)).isNull();
        } finally {
            realtimeSessionHub.unregister(agent.session());
        }
    }

    @Test
    void enablingPersonalAutoAcceptDrainsOnlyBatchAndNeverCrossesThreshold() {
        AgentFixture agent = createAgent("batch-agent", false, 5, 1);
        try {
            for (int index = 0; index < 5; index++) {
                createWaitingConversation(880_020L + index, "batch-message-" + index);
            }

            customerServiceService.updatePersonalSettings(
                    agent.principal(),
                    new PersonalSettingsUpdateRequest("批次客服", true, 3, 2)
            );
            assertThat(activeConversationCount(agent.adminUserId())).isEqualTo(2);
            assertThat(waitingConversationCount()).isEqualTo(3);

            customerServiceService.updatePersonalSettings(
                    agent.principal(),
                    new PersonalSettingsUpdateRequest("批次客服", true, 3, 2)
            );
            assertThat(activeConversationCount(agent.adminUserId())).isEqualTo(3);
            assertThat(waitingConversationCount()).isEqualTo(2);
        } finally {
            realtimeSessionHub.unregister(agent.session());
        }
    }

    @Test
    void nonWeightedRoutingIgnoresConfiguredMaximumCapacity() {
        AgentFixture agent = createAgent("unlimited-agent", false, 10, 5);
        try {
            managementService.updateRouting(1L, new CustomerServiceRoutingUpdateRequest(
                    "LEAST_LOADED", true, 48, List.of()
            ));
            for (int index = 0; index < 3; index++) {
                createWaitingConversation(880_030L + index, "unlimited-message-" + index);
            }
            jdbcClient.sql("""
                            update customer_service_agent_state
                            set max_active_conversations = 1
                            where admin_user_id = :adminUserId
                            """)
                    .param("adminUserId", agent.adminUserId())
                    .update();

            customerServiceService.updatePersonalSettings(
                    agent.principal(),
                    new PersonalSettingsUpdateRequest("无上限客服", true, 10, 5)
            );

            assertThat(activeConversationCount(agent.adminUserId())).isEqualTo(3);
            assertThat(customerServiceService.agentState(agent.principal()).canReceive()).isTrue();
        } finally {
            realtimeSessionHub.unregister(agent.session());
        }
    }

    @Test
    void weightedRoutingRequiresCapacityAndTreatsNullCapacityAsUnavailable() {
        AgentFixture agent = createAgent("weighted-agent", false, 10, 5);
        try {
            assertThatThrownBy(() -> managementService.updateRouting(
                    1L,
                    new CustomerServiceRoutingUpdateRequest(
                            "WEIGHTED",
                            true,
                            48,
                            List.of(new RoutingAgentUpdateRequest(agent.adminUserId(), null))
                    )
            )).isInstanceOf(BusinessException.class);

            managementService.updateRouting(1L, new CustomerServiceRoutingUpdateRequest(
                    "WEIGHTED",
                    true,
                    48,
                    List.of(new RoutingAgentUpdateRequest(agent.adminUserId(), 1))
            ));
            createWaitingConversation(880_040L, "weighted-message-1");
            createWaitingConversation(880_041L, "weighted-message-2");
            customerServiceService.updatePersonalSettings(
                    agent.principal(),
                    new PersonalSettingsUpdateRequest("权重客服", true, 10, 5)
            );
            assertThat(activeConversationCount(agent.adminUserId())).isEqualTo(1);
            assertThat(waitingConversationCount()).isEqualTo(1);

            jdbcClient.sql("""
                            update customer_service_agent_state
                            set max_active_conversations = null
                            where admin_user_id = :adminUserId
                            """)
                    .param("adminUserId", agent.adminUserId())
                    .update();
            assertThat(customerServiceService.agentState(agent.principal()).canReceive()).isFalse();
            createWaitingConversation(880_042L, "weighted-message-3");
            assertThat(waitingConversationCount()).isEqualTo(2);
        } finally {
            realtimeSessionHub.unregister(agent.session());
        }
    }

    @Test
    void stickyVisitorPreferenceRunsBeforeLeastLoadedStrategy() {
        AgentFixture stickyAgent = createAgent("sticky-agent", true, 10, 5);
        AgentFixture leastLoadedAgent = createAgent("least-loaded-agent", false, 10, 5);
        try {
            managementService.updateRouting(1L, new CustomerServiceRoutingUpdateRequest(
                    "LEAST_LOADED", true, 48, List.of()
            ));
            long returningConversationId = createWaitingConversation(
                    880_050L, "sticky-first-message"
            );
            assertThat(assignedAdminUserId(returningConversationId))
                    .isEqualTo(stickyAgent.adminUserId());
            customerServiceService.close(stickyAgent.principal(), returningConversationId);

            createWaitingConversation(880_051L, "sticky-agent-load-message");
            assertThat(activeConversationCount(stickyAgent.adminUserId())).isEqualTo(1);
            customerServiceService.updatePersonalSettings(
                    leastLoadedAgent.principal(),
                    new PersonalSettingsUpdateRequest("最少会话客服", true, 10, 5)
            );

            AuthenticatedPrincipal appPrincipal = appPrincipal(880_050L);
            customerServiceService.openForApp(appPrincipal, "GENERAL", null, null);
            customerServiceService.sendFromApp(
                    appPrincipal,
                    new SendMessageRequest("再次咨询", "sticky-return-message")
            );

            assertThat(assignedAdminUserId(returningConversationId))
                    .isEqualTo(stickyAgent.adminUserId());
            assertThat(activeConversationCount(leastLoadedAgent.adminUserId())).isZero();
        } finally {
            realtimeSessionHub.unregister(stickyAgent.session());
            realtimeSessionHub.unregister(leastLoadedAgent.session());
        }
    }

    private long insertAdmin() {
        return insertAdmin("auto-agent-" + UUID.randomUUID());
    }

    private long insertAdmin(String username) {
        jdbcClient.sql("""
                        insert into admin_user (
                            username, password_hash, display_name, email, avatar,
                            status, created_at, updated_at
                        )
                        values (
                            :username, 'unused', '自动分流客服', :email, '',
                            'ENABLED', current_timestamp, current_timestamp
                        )
                        """)
                .param("username", username)
                .param("email", username + "@shop.local")
                .update();
        long adminUserId = jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        select :adminUserId, id from admin_role where code = 'R_GUEST'
                        """)
                .param("adminUserId", adminUserId)
                .update();
        return adminUserId;
    }

    private long insertAppUser() {
        return insertAppUser(880_001L);
    }

    private long insertAppUser(long appUserId) {
        jdbcClient.sql("""
                        insert into app_user (
                            id, openid, nickname, status, created_at, updated_at
                        )
                        values (
                            :appUserId, :openid, '分流测试用户', 'ENABLED',
                            current_timestamp, current_timestamp
                        )
                        """)
                .param("appUserId", appUserId)
                .param("openid", "auto-assign-openid-" + UUID.randomUUID())
                .update();
        return appUserId;
    }

    private AgentFixture createAgent(
            String username,
            boolean autoAcceptEnabled,
            int autoAcceptBelow,
            int autoAcceptCount
    ) {
        long adminUserId = insertAdmin(username);
        managementService.addUser(
                1L,
                adminUserId,
                new ManagedUserCreateRequest(username + "客服")
        );
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                username,
                List.of("R_CUSTOMER_SERVICE"),
                List.of("customer-service:conversation:read")
        );
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("auto-assignment-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                username,
                principal.permissions()
        ));
        customerServiceService.updatePersonalSettings(
                principal,
                new PersonalSettingsUpdateRequest(
                        username + "客服", autoAcceptEnabled, autoAcceptBelow, autoAcceptCount
                )
        );
        customerServiceService.updateAgentState(principal, "AVAILABLE");
        return new AgentFixture(adminUserId, principal, session);
    }

    private long createWaitingConversation(long appUserId, String clientMessageId) {
        insertAppUser(appUserId);
        AuthenticatedPrincipal principal = appPrincipal(appUserId);
        customerServiceService.openForApp(principal, "GENERAL", null, null);
        customerServiceService.sendFromApp(
                principal,
                new SendMessageRequest("你好", clientMessageId)
        );
        return jdbcClient.sql("""
                        select id from customer_service_conversation where app_user_id = :appUserId
                        """)
                .param("appUserId", appUserId)
                .query(Long.class)
                .single();
    }

    private AuthenticatedPrincipal appPrincipal(long appUserId) {
        return new AuthenticatedPrincipal(
                TokenKind.APP,
                appUserId,
                "app-user-" + appUserId,
                List.of(),
                List.of()
        );
    }

    private String conversationStatus(long conversationId) {
        return jdbcClient.sql("select status from customer_service_conversation where id = :id")
                .param("id", conversationId)
                .query(String.class)
                .single();
    }

    private Long assignedAdminUserId(long conversationId) {
        return jdbcClient.sql("""
                        select assigned_admin_user_id
                        from customer_service_conversation
                        where id = :id
                        """)
                .param("id", conversationId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private int activeConversationCount(long adminUserId) {
        return jdbcClient.sql("""
                        select count(*)
                        from customer_service_conversation
                        where status = 'ACTIVE' and assigned_admin_user_id = :adminUserId
                        """)
                .param("adminUserId", adminUserId)
                .query(Integer.class)
                .single();
    }

    private int waitingConversationCount() {
        return jdbcClient.sql("""
                        select count(*)
                        from customer_service_conversation
                        where status = 'WAITING' and assigned_admin_user_id is null
                        """)
                .query(Integer.class)
                .single();
    }

    private record AgentFixture(
            long adminUserId,
            AuthenticatedPrincipal principal,
            WebSocketSession session
    ) {
    }
}
