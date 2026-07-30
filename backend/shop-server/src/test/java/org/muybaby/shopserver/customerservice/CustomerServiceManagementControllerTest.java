package org.muybaby.shopserver.customerservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
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

    @Test
    void superAdminCanAssignAgentAndManagerWithPerAgentServiceName() throws Exception {
        String token = loginAndExtractToken();
        long adminUserId = insertAdmin("service-member");

        mockMvc.perform(put("/admin/customer-service/management/users/{adminUserId}", adminUserId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agent": true,
                                  "manager": true,
                                  "serviceNameOverride": "小满",
                                  "maxActiveConversations": 8,
                                  "routingWeight": 120
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adminUserId").value(String.valueOf(adminUserId)))
                .andExpect(jsonPath("$.data.agent").value(true))
                .andExpect(jsonPath("$.data.manager").value(true))
                .andExpect(jsonPath("$.data.serviceName").value("小满"))
                .andExpect(jsonPath("$.data.serviceNameOverride").value("小满"))
                .andExpect(jsonPath("$.data.maxActiveConversations").value(8))
                .andExpect(jsonPath("$.data.routingWeight").value(120));

        assertThat(jdbcClient.sql("""
                        select role_item.code
                        from admin_user_role user_role
                        join admin_role role_item on role_item.id = user_role.role_id
                        where user_role.user_id = :adminUserId
                          and role_item.code in (
                              'R_CUSTOMER_SERVICE',
                              'R_CUSTOMER_SERVICE_MANAGER'
                          )
                        order by role_item.code
                        """)
                .param("adminUserId", adminUserId)
                .query(String.class)
                .list()).containsExactly(
                        "R_CUSTOMER_SERVICE",
                        "R_CUSTOMER_SERVICE_MANAGER"
                );

        mockMvc.perform(get("/admin/customer-service/management/users")
                        .header("Authorization", bearer(token))
                        .param("keyword", "小满"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].adminUserId", hasItem(String.valueOf(adminUserId))));
    }

    @Test
    void customerServiceManagerCanUpdateDefaultIdentityAndRouting() throws Exception {
        String managerToken = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of(
                        "customer-service:management:read",
                        "customer-service:routing:update",
                        "customer-service:identity:update"
                )
        );

        mockMvc.perform(put("/admin/customer-service/management/config")
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultServiceName": "MuY 客服",
                                  "avatar": "https://cdn.example.com/service.png",
                                  "autoAssignEnabled": true,
                                  "assignmentStrategy": "ROUND_ROBIN",
                                  "stickyAgentEnabled": true,
                                  "stickyWindowHours": 72
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultServiceName").value("MuY 客服"))
                .andExpect(jsonPath("$.data.avatar")
                        .value("https://cdn.example.com/service.png"))
                .andExpect(jsonPath("$.data.autoAssignEnabled").value(true))
                .andExpect(jsonPath("$.data.assignmentStrategy").value("ROUND_ROBIN"))
                .andExpect(jsonPath("$.data.stickyWindowHours").value(72));
    }

    @Test
    void nonSuperCustomerServiceManagerCannotGrantManagerRole() throws Exception {
        String managerToken = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of(
                        "customer-service:management:read",
                        "customer-service:agent:manage"
                )
        );
        long adminUserId = insertAdmin("manager-role-candidate");

        mockMvc.perform(put("/admin/customer-service/management/users/{adminUserId}", adminUserId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agent": true,
                                  "manager": true,
                                  "serviceNameOverride": "小满",
                                  "maxActiveConversations": 5,
                                  "routingWeight": 100
                                }
                                """))
                .andExpect(status().isForbidden());

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_user_role user_role
                        join admin_role role_item on role_item.id = user_role.role_id
                        where user_role.user_id = :adminUserId
                          and role_item.code = 'R_CUSTOMER_SERVICE_MANAGER'
                        """)
                .param("adminUserId", adminUserId)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void readPermissionAloneCannotMutateCustomerServiceConfig() throws Exception {
        String readOnlyToken = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("customer-service:management:read")
        );

        mockMvc.perform(get("/admin/customer-service/management/config")
                        .header("Authorization", bearer(readOnlyToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultServiceName").value("商城客服"));

        mockMvc.perform(put("/admin/customer-service/management/config")
                        .header("Authorization", bearer(readOnlyToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultServiceName": "越权修改",
                                  "avatar": "",
                                  "autoAssignEnabled": false,
                                  "assignmentStrategy": "LEAST_LOADED",
                                  "stickyAgentEnabled": true,
                                  "stickyWindowHours": 48
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void weightedAssignmentStrategyIsAccepted() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(put("/admin/customer-service/management/config")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "defaultServiceName": "商城客服",
                                  "avatar": "",
                                  "autoAssignEnabled": true,
                                  "assignmentStrategy": "WEIGHTED",
                                  "stickyAgentEnabled": false,
                                  "stickyWindowHours": 48
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignmentStrategy").value("WEIGHTED"));
    }

    private long insertAdmin(String username) {
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
        return jdbcClient.sql("select id from admin_user where username = :username")
                .param("username", username)
                .query(Long.class)
                .single();
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
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
