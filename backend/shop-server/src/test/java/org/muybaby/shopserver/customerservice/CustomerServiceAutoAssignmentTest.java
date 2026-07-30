package org.muybaby.shopserver.customerservice;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.SendMessageRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserUpdateRequest;
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
        managementService.updateUser(
                1L,
                adminUserId,
                new ManagedUserUpdateRequest(true, false, "小满", 5, 100)
        );
        managementService.updateConfig(
                1L,
                new CustomerServiceConfigUpdateRequest(
                        "商城客服",
                        "https://cdn.example.com/service.png",
                        true,
                        "LEAST_LOADED",
                        true,
                        48
                )
        );

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

    private long insertAdmin() {
        String username = "auto-agent-" + UUID.randomUUID();
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
        return jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
    }

    private long insertAppUser() {
        long appUserId = 880_001L;
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
}
