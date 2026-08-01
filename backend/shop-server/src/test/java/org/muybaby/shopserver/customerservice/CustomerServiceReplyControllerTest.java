package org.muybaby.shopserver.customerservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
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
class CustomerServiceReplyControllerTest {

    private static final List<String> FULL_PERMISSIONS = List.of(
            "customer-service:auto-reply:read",
            "customer-service:auto-reply:welcome:update",
            "customer-service:auto-reply:update",
            "customer-service:quick-reply:read",
            "customer-service:quick-reply:update"
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void managerCanMaintainAutoRepliesAndSharedQuickReplies() throws Exception {
        String token = issueToken(FULL_PERMISSIONS);
        long adminUserId = tokenUserId(token);
        insertAgentProfile(adminUserId);

        mockMvc.perform(get("/admin/customer-service/auto-replies")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(0))
                .andExpect(jsonPath("$.data.openingMessage").value(""))
                .andExpect(jsonPath("$.data.welcomeMessage").value(""))
                .andExpect(jsonPath("$.data.offlineMessage").value(""))
                .andExpect(jsonPath("$.data.commonQuestions.length()").value(0))
                .andExpect(jsonPath("$.data.smartReplies.length()").value(0));

        String commonResponse = mockMvc.perform(put("/admin/customer-service/auto-replies/common")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revision":0,
                                  "openingMessage":"您好，欢迎咨询",
                                  "commonQuestions":[{
                                    "question":"什么时候发货",
                                    "answer":"付款后 48 小时内发货",
                                    "enabled":true,
                                    "sortOrder":0
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.openingMessage").value("您好，欢迎咨询"))
                .andExpect(jsonPath("$.data.commonQuestions[0].questionId").isString())
                .andReturn().getResponse().getContentAsString();
        String questionId = objectMapper.readTree(commonResponse)
                .path("data").path("commonQuestions").path(0).path("questionId").asText();
        assertThat(questionId).isNotBlank();

        mockMvc.perform(put("/admin/customer-service/auto-replies/welcome")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"您好，我是小满客服\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.welcomeMessage").value("您好，我是小满客服"));

        mockMvc.perform(put("/admin/customer-service/auto-replies/offline")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"content\":\"当前客服均已离线\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.offlineMessage").value("当前客服均已离线"));

        mockMvc.perform(put("/admin/customer-service/auto-replies/smart")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revision":2,
                                  "smartReplies":[
                                    {
                                      "name":"物流问题",
                                      "questions":["快递到哪了","查询物流"],
                                      "reply":"请在订单详情查看物流进度",
                                      "enabled":true,
                                      "sortOrder":0
                                    },
                                    {
                                      "name":"第一组",
                                      "questions":[],
                                      "reply":"",
                                      "enabled":false,
                                      "sortOrder":1
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.smartReplies.length()").value(2))
                .andExpect(jsonPath("$.data.smartReplies[0].replyId").isString())
                .andExpect(jsonPath("$.data.smartReplies[1].name").value("第一组"))
                .andExpect(jsonPath("$.data.smartReplies[1].questions.length()").value(0))
                .andExpect(jsonPath("$.data.smartReplies[1].reply").value(""))
                .andExpect(jsonPath("$.data.smartReplies[1].enabled").value(false));

        mockMvc.perform(get("/app/customer-service/conversation/common-questions"))
                .andExpect(status().isUnauthorized());

        String groupResponse = mockMvc.perform(post("/admin/customer-service/quick-reply-groups")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"售前咨询\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupId").isString())
                .andExpect(jsonPath("$.data.name").value("售前咨询"))
                .andExpect(jsonPath("$.data.replies.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String groupId = objectMapper.readTree(groupResponse)
                .path("data").path("groupId").asText();

        String quickResponse = mockMvc.perform(post("/admin/customer-service/quick-replies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"1\",\"content\":\"您好，请稍等，我马上为您查询\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replyId").isString())
                .andExpect(jsonPath("$.data.content").value("您好，请稍等，我马上为您查询"))
                .andReturn().getResponse().getContentAsString();
        String replyId = objectMapper.readTree(quickResponse)
                .path("data").path("replyId").asText();

        mockMvc.perform(get("/admin/customer-service/quick-replies")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].groupId").value("1"))
                .andExpect(jsonPath("$.data.groups[0].name").value("默认分组"))
                .andExpect(jsonPath("$.data.groups[0].replies[0].replyId").value(replyId))
                .andExpect(jsonPath("$.data.groups[1].groupId").value(groupId))
                .andExpect(jsonPath("$.data.groups[1].name").value("售前咨询"));

        mockMvc.perform(put("/admin/customer-service/quick-replies/{replyId}", replyId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"已为您查询，请查收\",\"sortOrder\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("已为您查询，请查收"))
                .andExpect(jsonPath("$.data.sortOrder").value(3));

        mockMvc.perform(delete("/admin/customer-service/quick-replies/{replyId}", replyId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/customer-service/quick-replies")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groups[0].replies.length()").value(0));
    }

    @Test
    void regularAgentCanReadAndEditOnlyOwnWelcomeMessage() throws Exception {
        List<String> agentPermissions = List.of(
                "customer-service:auto-reply:read",
                "customer-service:auto-reply:welcome:update",
                "customer-service:quick-reply:read"
        );
        String firstToken = issueToken(agentPermissions);
        String secondToken = issueToken(agentPermissions);
        long firstUserId = tokenUserId(firstToken);
        long secondUserId = tokenUserId(secondToken);
        insertAgentProfile(firstUserId);
        insertAgentProfile(secondUserId);

        mockMvc.perform(put("/admin/customer-service/auto-replies/welcome")
                        .header("Authorization", bearer(firstToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"一号客服欢迎您\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.welcomeMessage").value("一号客服欢迎您"));

        mockMvc.perform(get("/admin/customer-service/auto-replies")
                        .header("Authorization", bearer(secondToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.welcomeMessage").value(""));

        assertThat(jdbcClient.sql("""
                        select welcome_message
                        from customer_service_agent_profile
                        where admin_user_id = :adminUserId
                        """)
                .param("adminUserId", secondUserId)
                .query(String.class)
                .single()).isEmpty();

        mockMvc.perform(put("/admin/customer-service/auto-replies/common")
                        .header("Authorization", bearer(firstToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"openingMessage\":\"越权\",\"commonQuestions\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/customer-service/auto-replies/offline")
                        .header("Authorization", bearer(firstToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"content\":\"越权\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/customer-service/auto-replies/smart")
                        .header("Authorization", bearer(firstToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"smartReplies\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/customer-service/quick-replies")
                        .header("Authorization", bearer(firstToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"1\",\"content\":\"越权\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/customer-service/quick-reply-groups")
                        .header("Authorization", bearer(firstToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权分组\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/customer-service/quick-replies")
                        .header("Authorization", bearer(firstToken)))
                .andExpect(status().isOk());
    }

    @Test
    void enabledSmartReplyRequiresQuestionAndReply() throws Exception {
        String token = issueToken(FULL_PERMISSIONS);

        mockMvc.perform(put("/admin/customer-service/auto-replies/smart")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revision":0,
                                  "smartReplies":[{
                                    "name":"第一组",
                                    "questions":[],
                                    "reply":"",
                                    "enabled":true,
                                    "sortOrder":0
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stalePublicAutoReplyRevisionIsRejectedWithoutOverwritingLatestConfig() throws Exception {
        String token = issueToken(FULL_PERMISSIONS);

        mockMvc.perform(put("/admin/customer-service/auto-replies/common")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revision":0,
                                  "openingMessage":"第一位管理员保存的开场白",
                                  "commonQuestions":[]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        mockMvc.perform(put("/admin/customer-service/auto-replies/offline")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"revision":0,"content":"陈旧页面提交的离线回复"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(900002));

        mockMvc.perform(get("/admin/customer-service/auto-replies")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.openingMessage")
                        .value("第一位管理员保存的开场白"))
                .andExpect(jsonPath("$.data.offlineMessage").value(""));
    }

    private String issueToken(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }

    private long tokenUserId(String token) {
        return opaqueTokenService.lookupAccessToken(token, TokenKind.ADMIN)
                .orElseThrow()
                .subjectId();
    }

    private void insertAgentProfile(long adminUserId) {
        jdbcClient.sql("""
                        insert into customer_service_agent_profile (admin_user_id)
                        values (:adminUserId)
                        """)
                .param("adminUserId", adminUserId)
                .update();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
