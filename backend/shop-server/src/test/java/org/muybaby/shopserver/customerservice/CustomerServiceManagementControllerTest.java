package org.muybaby.shopserver.customerservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.realtime.RealtimeConnectionPrincipal;
import org.muybaby.shopserver.realtime.RealtimeSessionHub;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CustomerServiceManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private RealtimeSessionHub realtimeSessionHub;

    @Test
    void superAdminManagesAgentsOnlyThroughGuestPromotionEndpoints() throws Exception {
        String token = loginAndExtractToken();
        long adminUserId = insertGuest("service-member");

        mockMvc.perform(get("/admin/customer-service/management/guests")
                        .header("Authorization", bearer(token))
                        .param("keyword", "service-member"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].adminUserId", hasItem(String.valueOf(adminUserId))));

        mockMvc.perform(post("/admin/customer-service/management/users/{adminUserId}", adminUserId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"小满\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adminUserId").value(String.valueOf(adminUserId)))
                .andExpect(jsonPath("$.data.username").value("service-member"))
                .andExpect(jsonPath("$.data.serviceName").value("小满"))
                .andExpect(jsonPath("$.data.online").value(false))
                .andExpect(jsonPath("$.data.manager").value(false))
                .andExpect(jsonPath("$.data.boundAt").isNotEmpty());

        mockMvc.perform(put("/admin/customer-service/management/users/{adminUserId}/name", adminUserId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"小满客服\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceName").value("小满客服"));

        mockMvc.perform(put("/admin/customer-service/management/users/{adminUserId}/manager", adminUserId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manager\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manager").value(true));

        mockMvc.perform(get("/admin/customer-service/management/users")
                        .header("Authorization", bearer(token))
                        .param("keyword", "小满客服"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].adminUserId", hasItem(String.valueOf(adminUserId))));

        mockMvc.perform(delete("/admin/customer-service/management/users/{adminUserId}", adminUserId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        assertThat(roleCodes(adminUserId)).containsExactly("R_GUEST");
        assertThat(jdbcClient.sql("""
                        select auto_accept_enabled
                        from customer_service_agent_profile
                        where admin_user_id = :adminUserId
                        """)
                .param("adminUserId", adminUserId)
                .query(Boolean.class)
                .single()).isFalse();
    }

    @Test
    void customerServiceManagerCannotManageMembers() throws Exception {
        String managerToken = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of(
                        "customer-service:management:read",
                        "customer-service:routing:update",
                        "customer-service:identity:update"
                )
        );
        long adminUserId = insertGuest("manager-role-candidate");

        mockMvc.perform(get("/admin/customer-service/management/users")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/customer-service/management/users/{adminUserId}", adminUserId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managementAndPublicPresenceRequireManualSwitchAndRealtimeWorkspace()
            throws Exception {
        String token = loginAndExtractToken();
        long adminUserId = promoteGuest(token, "manual-online-agent", "手动在线客服");
        jdbcClient.sql("""
                        update customer_service_agent_state
                        set work_status = 'AVAILABLE'
                        where admin_user_id = :adminUserId
                        """)
                .param("adminUserId", adminUserId)
                .update();

        mockMvc.perform(get("/admin/customer-service/management/users")
                        .header("Authorization", bearer(token))
                        .param("keyword", "manual-online-agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].online").value(false));
        mockMvc.perform(get("/app/customer-service/presence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.online").value(false));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("management-presence-" + UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        realtimeSessionHub.register(session, new RealtimeConnectionPrincipal(
                TokenKind.ADMIN,
                adminUserId,
                "manual-online-agent",
                List.of("customer-service:conversation:read")
        ));
        realtimeSessionHub.startCustomerServicePresence(session);

        try {
            mockMvc.perform(get("/admin/customer-service/management/users")
                            .header("Authorization", bearer(token))
                            .param("keyword", "manual-online-agent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].online").value(true));
            mockMvc.perform(get("/admin/customer-service/management/config")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.data.routingAgents[?(@.adminUserId == '%s')].online"
                                    .formatted(adminUserId),
                            hasItem(true)
                    ));
            mockMvc.perform(get("/app/customer-service/presence"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.online").value(true));
        } finally {
            realtimeSessionHub.unregister(session);
        }
    }

    @Test
    void weightedRoutingRequiresEveryAgentCapacityAndReturnsDerivedWeights() throws Exception {
        String token = loginAndExtractToken();
        long firstAgentId = promoteGuest(token, "weighted-agent-a", "甲客服");
        long secondAgentId = promoteGuest(token, "weighted-agent-b", "乙客服");

        List<Map<String, Object>> agents = List.of(
                routingAgent(firstAgentId, 2),
                routingAgent(secondAgentId, 6)
        );
        JsonNode response = performRoutingUpdate(token, "WEIGHTED", agents);
        assertThat(response.path("assignmentStrategy").asText()).isEqualTo("WEIGHTED");
        Map<Long, JsonNode> agentsById = new LinkedHashMap<>();
        response.path("routingAgents").forEach(agent ->
                agentsById.put(agent.path("adminUserId").asLong(), agent));
        assertThat(agentsById.get(firstAgentId).path("calculatedWeight").asInt()).isEqualTo(2);
        assertThat(agentsById.get(firstAgentId).path("calculatedWeightPercent").asDouble())
                .isEqualTo(25D);
        assertThat(agentsById.get(secondAgentId).path("calculatedWeight").asInt()).isEqualTo(6);
        assertThat(agentsById.get(secondAgentId).path("calculatedWeightPercent").asDouble())
                .isEqualTo(75D);

        mockMvc.perform(put("/admin/customer-service/management/routing")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingBody("WEIGHTED", List.of(routingAgent(firstAgentId, 2)))))
                .andExpect(status().isBadRequest());

        performRoutingUpdate(token, "LEAST_LOADED", List.of());
        assertThat(jdbcClient.sql("""
                        select assignment_strategy from customer_service_config where id = 1
                        """)
                .query(String.class)
                .single()).isEqualTo("LEAST_LOADED");
    }

    @Test
    void onlyIdentityPermissionCanSaveServerResolvedPublicAvatar() throws Exception {
        String managerToken = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("customer-service:management:read", "customer-service:identity:update")
        );
        long avatarFileId = insertPublicImageAsset();

        mockMvc.perform(put("/admin/customer-service/management/identity")
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "defaultServiceName", "MuY 客服",
                                "avatarFileId", avatarFileId
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultServiceName").value("MuY 客服"))
                .andExpect(jsonPath("$.data.avatar").value("https://cdn.example.com/service.png"))
                .andExpect(jsonPath("$.data.avatarFileId").value(avatarFileId));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :assetId
                          and owner_type = 'CUSTOMER_SERVICE_CONFIG'
                          and usage_type = 'CUSTOMER_SERVICE_AVATAR'
                          and protected = true
                          and status = 'ACTIVE'
                        """)
                .param("assetId", avatarFileId)
                .query(Integer.class)
                .single()).isEqualTo(1);

        String superToken = loginAndExtractToken();
        mockMvc.perform(put("/admin/customer-service/management/identity")
                        .header("Authorization", bearer(superToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultServiceName\":\"越权修改\",\"avatarFileId\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void readPermissionAloneCannotMutateRoutingOrIdentity() throws Exception {
        String readOnlyToken = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("customer-service:management:read")
        );

        mockMvc.perform(get("/admin/customer-service/management/config")
                        .header("Authorization", bearer(readOnlyToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultServiceName").value("商城客服"));

        mockMvc.perform(put("/admin/customer-service/management/routing")
                        .header("Authorization", bearer(readOnlyToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingBody("LEAST_LOADED", List.of())))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/customer-service/management/identity")
                        .header("Authorization", bearer(readOnlyToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultServiceName\":\"越权修改\",\"avatarFileId\":null}"))
                .andExpect(status().isForbidden());
    }

    private JsonNode performRoutingUpdate(
            String token,
            String assignmentStrategy,
            List<Map<String, Object>> agents
    ) throws Exception {
        String content = mockMvc.perform(put("/admin/customer-service/management/routing")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(routingBody(assignmentStrategy, agents)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(content).path("data");
    }

    private String routingBody(String assignmentStrategy, List<Map<String, Object>> agents)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentStrategy", assignmentStrategy);
        body.put("stickyAgentEnabled", true);
        body.put("stickyWindowHours", 48);
        body.put("agents", agents);
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> routingAgent(long adminUserId, int maxActiveConversations) {
        return Map.of(
                "adminUserId", adminUserId,
                "maxActiveConversations", maxActiveConversations
        );
    }

    private long promoteGuest(String token, String username, String serviceName) throws Exception {
        long adminUserId = insertGuest(username);
        mockMvc.perform(post("/admin/customer-service/management/users/{adminUserId}", adminUserId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("serviceName", serviceName))))
                .andExpect(status().isOk());
        return adminUserId;
    }

    private long insertGuest(String username) {
        jdbcClient.sql("""
                        insert into admin_user (
                            username, password_hash, display_name, email, avatar,
                            status, created_at, updated_at
                        )
                        values (
                            :username, 'unused', '客服候选人', :email, '',
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

    private List<String> roleCodes(long adminUserId) {
        return new ArrayList<>(jdbcClient.sql("""
                        select role_item.code
                        from admin_user_role user_role
                        join admin_role role_item on role_item.id = user_role.role_id
                        where user_role.user_id = :adminUserId
                        order by role_item.code
                        """)
                .param("adminUserId", adminUserId)
                .query(String.class)
                .list());
    }

    private long insertPublicImageAsset() {
        String objectKey = "public/customer-service/" + UUID.randomUUID() + ".png";
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider,
                             storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height,
                             alt_text, tags_json, public_url, status,
                             uploaded_by_type, uploaded_by_id)
                        values
                            ('LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS',
                             '', :objectKey, 'service.png',
                             'image/png', 'png', 68, 'avatar-sha', 1, 1,
                             '', null, 'https://cdn.example.com/service.png', 'ACTIVE',
                             'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .update();
        return jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"Super\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
