package org.muybaby.shopserver.customerservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.realtime.RealtimeConnectionPrincipal;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CustomerServiceControlledTransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RealtimeSessionHub realtimeSessionHub;

    @BeforeEach
    void clearState() {
        jdbcClient.sql("delete from customer_service_transfer_request").update();
        jdbcClient.sql("delete from customer_service_agent_state").update();
        jdbcClient.sql("delete from customer_service_consultation_resource").update();
        jdbcClient.sql("delete from customer_service_conversation_order").update();
        jdbcClient.sql("delete from customer_service_assignment_log").update();
        jdbcClient.sql("delete from customer_service_message").update();
        jdbcClient.sql("delete from customer_service_conversation").update();
    }

    @Test
    void claimRequiresOnlineAvailableAgentAndRespectsCapacity() throws Exception {
        String superToken = adminLogin("Super", "123456");
        long conversationId = openWaitingConversation("controlled-claim-one");

        mockMvc.perform(get("/admin/customer-service/profile")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceName").value("商城客服"))
                .andExpect(jsonPath("$.data.avatar").value(""));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900005));

        connectAdmin(1L);
        setWorkStatus(superToken, "OFFLINE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workStatus").value("OFFLINE"));
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900005));

        setWorkStatus(superToken, "AVAILABLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(true));
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAdminUserId").value(1));

        jdbcClient.sql("update customer_service_agent_state set max_active_conversations = 1 where admin_user_id = 1")
                .update();
        jdbcClient.sql("update customer_service_config set assignment_strategy = 'WEIGHTED' where id = 1")
                .update();
        long secondConversationId = openWaitingConversation("controlled-claim-two");
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", secondConversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900006));
    }

    @Test
    void regularTransferRequiresOnlineAvailableTargetAndChangesOwnerOnlyAfterAcceptance() throws Exception {
        String superToken = adminLogin("Super", "123456");
        long targetId = insertCustomerServiceAgent("TransferTarget", "转接客服", "agent-pass");
        String targetToken = adminLogin("TransferTarget", "agent-pass");
        long conversationId = claimAsSuper(superToken, "controlled-transfer-accept");

        setWorkStatus(targetToken, "AVAILABLE").andExpect(status().isOk());
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/transfer-requests", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, "EXPERTISE", "需要商品专家处理")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900005));

        connectAdmin(targetId);
        String agentsResponse = mockMvc.perform(get("/admin/customer-service/agents")
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode agents = objectMapper.readTree(agentsResponse).path("data");
        JsonNode target = null;
        for (JsonNode agent : agents) {
            assertThat(agent.path("adminUserId").asLong()).isNotEqualTo(1L);
            if (agent.path("adminUserId").asLong() == targetId) {
                target = agent;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.path("online").asBoolean()).isTrue();
        assertThat(target.path("workStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(target.path("activeConversationCount").asInt()).isZero();
        assertThat(target.path("maxActiveConversations").isMissingNode()).isTrue();
        assertThat(target.path("canReceive").asBoolean()).isTrue();

        String requested = mockMvc.perform(post(
                                "/admin/customer-service/conversations/{conversationId}/transfer-requests",
                                conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, "EXPERTISE", "需要商品专家处理")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.fromAdminUserId").value(1))
                .andExpect(jsonPath("$.data.toAdminUserId").value(targetId))
                .andReturn().getResponse().getContentAsString();
        long requestId = objectMapper.readTree(requested).path("data").path("requestId").asLong();

        mockMvc.perform(get("/admin/customer-service/conversations/{conversationId}", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAdminUserId").value(1));
        mockMvc.perform(get("/admin/customer-service/transfer-requests/pending")
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].requestId").value(requestId));

        mockMvc.perform(post("/admin/customer-service/transfer-requests/{requestId}/accept", requestId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAdminUserId").value(targetId));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"不应再发送","clientMessageId":"controlled-transfer-old-agent"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900003));
    }

    @Test
    void transferCanBeRejectedOrExpireAndConversationCanReturnToWaitingQueue() throws Exception {
        String superToken = adminLogin("Super", "123456");
        long targetId = insertCustomerServiceAgent("RejectTarget", "拒绝客服", "agent-pass");
        String targetToken = adminLogin("RejectTarget", "agent-pass");
        long conversationId = claimAsSuper(superToken, "controlled-transfer-reject");
        connectAdmin(targetId);
        setWorkStatus(targetToken, "AVAILABLE").andExpect(status().isOk());

        long firstRequestId = createTransferRequest(superToken, conversationId, targetId, "SHIFT", "交接班");
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/transfer-requests", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, "SHIFT", "重复申请")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900008));

        mockMvc.perform(post("/admin/customer-service/transfer-requests/{requestId}/reject", firstRequestId)
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(get("/admin/customer-service/conversations/{conversationId}", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAdminUserId").value(1));

        long expiringRequestId = createTransferRequest(
                superToken, conversationId, targetId, "OTHER", "等待超时"
        );
        jdbcClient.sql("""
                        update customer_service_transfer_request
                        set expires_at = dateadd('SECOND', -1, current_timestamp)
                        where id = :requestId
                        """)
                .param("requestId", expiringRequestId)
                .update();
        mockMvc.perform(get("/admin/customer-service/transfer-requests/pending")
                        .header("Authorization", bearer(targetToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbcClient.sql("select status from customer_service_transfer_request where id = :id")
                .param("id", expiringRequestId)
                .query(String.class)
                .single()).isEqualTo("TIMEOUT");

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/release", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.assignedAdminUserId").doesNotExist());
    }

    @Test
    void superCannotForceTransferToOfflineBusyOrWeightedFullAgent() throws Exception {
        String superToken = adminLogin("Super", "123456");
        long targetId = insertCustomerServiceAgent("ForceTarget", "强制目标客服", "agent-pass");
        String targetToken = adminLogin("ForceTarget", "agent-pass");
        long conversationId = claimAsSuper(superToken, "controlled-force-transfer");

        setWorkStatus(targetToken, "BUSY").andExpect(status().isOk());
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/force-transfer", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, "SUPERVISOR", "紧急交接")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900005));

        connectAdmin(targetId);
        setWorkStatus(targetToken, "AVAILABLE").andExpect(status().isOk());
        jdbcClient.sql("update customer_service_config set assignment_strategy = 'WEIGHTED' where id = 1")
                .update();
        jdbcClient.sql("""
                        update customer_service_agent_state
                        set max_active_conversations = 1
                        where admin_user_id = :targetId
                        """)
                .param("targetId", targetId)
                .update();
        insertAssignedConversation(targetId, "FORCE-TARGET-EXISTING");

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/transfer-requests", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, "OTHER", "普通转接应被限制")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900006));

        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/force-transfer", conversationId)
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, "SUPERVISOR", "紧急交接")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900006));
    }

    private long claimAsSuper(String superToken, String appCode) throws Exception {
        connectAdmin(1L);
        setWorkStatus(superToken, "AVAILABLE").andExpect(status().isOk());
        long conversationId = openWaitingConversation(appCode);
        mockMvc.perform(post("/admin/customer-service/conversations/{conversationId}/claim", conversationId)
                        .header("Authorization", bearer(superToken)))
                .andExpect(status().isOk());
        return conversationId;
    }

    private org.springframework.test.web.servlet.ResultActions setWorkStatus(
            String token,
            String workStatus
    ) throws Exception {
        return mockMvc.perform(put("/admin/customer-service/agent-state")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workStatus\":\"%s\"}".formatted(workStatus)));
    }

    private long createTransferRequest(
            String token,
            long conversationId,
            long targetId,
            String reasonCode,
            String reasonNote
    ) throws Exception {
        String response = mockMvc.perform(post(
                                "/admin/customer-service/conversations/{conversationId}/transfer-requests",
                                conversationId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(targetId, reasonCode, reasonNote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("requestId").asLong();
    }

    private String transferBody(long targetId, String reasonCode, String reasonNote) {
        return """
                {"targetAdminUserId":%d,"reasonCode":"%s","reasonNote":"%s"}
                """.formatted(targetId, reasonCode, reasonNote);
    }

    private long openWaitingConversation(String code) throws Exception {
        AppLogin app = appLogin(code);
        String opened = mockMvc.perform(post("/app/customer-service/conversation/open")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long conversationId = objectMapper.readTree(opened).path("data").path("conversationId").asLong();
        mockMvc.perform(post("/app/customer-service/conversation/messages")
                        .header("Authorization", bearer(app.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"需要人工客服","clientMessageId":"%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        return conversationId;
    }

    private void insertAssignedConversation(long targetId, String userCode) throws Exception {
        AppLogin app = appLogin(userCode);
        jdbcClient.sql("""
                        insert into customer_service_conversation
                            (app_user_id, status, assigned_admin_user_id, consultation_no,
                             context_type, activated_at, claimed_at, created_at, updated_at)
                        values
                            (:appUserId, 'ACTIVE', :targetId, 1,
                             'GENERAL', current_timestamp, current_timestamp, current_timestamp, current_timestamp)
                        """)
                .param("appUserId", app.userId())
                .param("targetId", targetId)
                .update();
    }

    private WebSocketSession connectAdmin(long adminUserId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("test-admin-" + adminUserId + "-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "admin-" + adminUserId,
                List.of("customer-service:conversation:read")
        ));
        return session;
    }

    private AppLogin appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new AppLogin(data.path("token").asText(), data.path("user").path("userId").asLong());
    }

    private String adminLogin(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long insertCustomerServiceAgent(String username, String displayName, String password) {
        jdbcClient.sql("""
                        insert into admin_user
                            (username, password_hash, display_name, email, avatar, status, created_at, updated_at)
                        values
                            (:username, :passwordHash, :displayName, :email, '', 'ENABLED', current_timestamp, current_timestamp)
                        """)
                .param("username", username)
                .param("passwordHash", passwordEncoder.encode(password))
                .param("displayName", displayName)
                .param("email", username.toLowerCase() + "@shop.local")
                .update();
        long userId = jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user_role (user_id, role_id)
                        select :userId, id from admin_role where code = 'R_CUSTOMER_SERVICE'
                        """)
                .param("userId", userId)
                .update();
        return userId;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppLogin(String token, long userId) {
    }
}
