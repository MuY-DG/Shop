package org.muybaby.shopserver.accountrights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountRightsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void appUserSubmitsInspectsAndWithdrawsOneActiveRequest() throws Exception {
        AppSession user = loginApp("rights-app-lifecycle");

        mockMvc.perform(post("/app/account-rights/requests")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestType", "ACCOUNT_CANCELLATION",
                                "requestNote", "请注销当前账户",
                                "wechatCode", "different-wechat-user"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(120004));

        long requestId = submitRequest(
                user,
                "ACCESS_COPY",
                "申请获取个人信息副本",
                null
        );

        mockMvc.perform(post("/app/account-rights/requests")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestType":"CORRECTION","requestNote":"申请更正资料"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(120002));

        mockMvc.perform(get("/app/account-rights/requests")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(Long.toString(requestId)))
                .andExpect(jsonPath("$.data[0].userId").value(Long.toString(user.userId())))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"));

        mockMvc.perform(get("/app/account-rights/requests/{requestId}", requestId)
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request.id").value(Long.toString(requestId)))
                .andExpect(jsonPath("$.data.audits[0].id").isString())
                .andExpect(jsonPath("$.data.audits[0].actorId").value(Long.toString(user.userId())))
                .andExpect(jsonPath("$.data.audits[0].action").value("SUBMITTED"));

        mockMvc.perform(post("/app/account-rights/requests/{requestId}/withdraw", requestId)
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.version").value(1));

        submitRequest(user, "CORRECTION", "重新申请更正资料", null);
    }

    @Test
    void adminCompletesCancellationAtomicallyAndOldAccessAndRefreshTokensFail() throws Exception {
        String loginCode = "rights-cancellation-complete";
        AppSession user = loginApp(loginCode);
        jdbcClient.sql("""
                        update app_user
                        set unionid = 'union-sensitive',
                            nickname = '待注销用户',
                            avatar_url = 'https://example.test/avatar.png',
                            phone_number = '13812345678',
                            phone_country_code = '86',
                            phone_authorized = true,
                            phone_authorized_at = current_timestamp
                        where id = :userId
                        """)
                .param("userId", user.userId())
                .update();
        long requestId = submitRequest(
                user,
                "ACCOUNT_CANCELLATION",
                "确认注销账户",
                loginCode
        );
        String adminToken = loginAdmin();

        mockMvc.perform(post("/admin/account-rights/requests/{requestId}/review", requestId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"开始核验\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        transition(adminToken, requestId, "review", 0);
        transition(adminToken, requestId, "approve", 1);
        mockMvc.perform(post("/admin/account-rights/requests/{requestId}/complete", requestId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.userStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.reviewedBy").isString())
                .andExpect(jsonPath("$.data.version").value(3));

        Map<String, Object> cancelledUser = jdbcClient.sql("""
                        select openid, unionid, nickname, avatar_url, phone_number,
                               phone_authorized, status, auth_version, cancelled_at
                        from app_user
                        where id = :userId
                        """)
                .param("userId", user.userId())
                .query()
                .singleRow();
        assertThat(cancelledUser.get("OPENID").toString())
                .startsWith("cancelled_")
                .doesNotContain(loginCode);
        assertThat(cancelledUser.get("UNIONID")).isNull();
        assertThat(cancelledUser.get("NICKNAME")).isEqualTo("");
        assertThat(cancelledUser.get("AVATAR_URL")).isNull();
        assertThat(cancelledUser.get("PHONE_NUMBER")).isNull();
        assertThat(cancelledUser.get("PHONE_AUTHORIZED")).isEqualTo(false);
        assertThat(cancelledUser.get("STATUS")).isEqualTo("CANCELLED");
        assertThat(cancelledUser.get("AUTH_VERSION")).isEqualTo(1L);
        assertThat(cancelledUser.get("CANCELLED_AT")).isNotNull();

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/account-rights/requests/{requestId}", requestId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audits.length()").value(4))
                .andExpect(jsonPath("$.data.audits[3].retainedDataCategories[0]")
                        .value("交易记录"));
    }

    @Test
    void activeCommerceObligationBlocksCompletionWithoutPartialCancellation() throws Exception {
        String loginCode = "rights-active-order";
        AppSession user = loginApp(loginCode);
        long requestId = submitRequest(
                user, "ACCOUNT_CANCELLATION", "有活跃订单时申请注销", loginCode);
        String adminToken = loginAdmin();
        transition(adminToken, requestId, "review", 0);
        transition(adminToken, requestId, "approve", 1);
        insertOrder(9_002_001L, user.userId(), "PAID");

        mockMvc.perform(post("/admin/account-rights/requests/{requestId}/complete", requestId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody(2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(120005));

        assertThat(jdbcClient.sql("select status from app_user where id = :userId")
                .param("userId", user.userId())
                .query(String.class)
                .single()).isEqualTo("ENABLED");
        assertThat(jdbcClient.sql("""
                        select concat(status, '|', version)
                        from app_user_rights_request
                        where id = :requestId
                        """)
                .param("requestId", requestId)
                .query(String.class)
                .single()).isEqualTo("APPROVED|2");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from app_user_rights_request_audit
                        where request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(Integer.class)
                .single()).isEqualTo(3);
    }

    @Test
    void personalInformationDeletionClearsOptionalIdentityButKeepsLoginIdentity() throws Exception {
        String loginCode = "rights-personal-delete";
        AppSession user = loginApp(loginCode);
        String originalOpenid = jdbcClient.sql("select openid from app_user where id = :userId")
                .param("userId", user.userId())
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        update app_user
                        set unionid = 'union-to-delete', nickname = '待删除资料',
                            avatar_url = 'https://example.test/delete.png',
                            phone_number = '13912345678', phone_country_code = '86',
                            phone_authorized = true, phone_authorized_at = current_timestamp
                        where id = :userId
                        """)
                .param("userId", user.userId())
                .update();
        long requestId = submitRequest(
                user, "PERSONAL_INFORMATION_DELETION", "删除可选个人资料", null);
        String adminToken = loginAdmin();
        transition(adminToken, requestId, "review", 0);
        transition(adminToken, requestId, "approve", 1);
        transition(adminToken, requestId, "complete", 2);

        Map<String, Object> remaining = jdbcClient.sql("""
                        select openid, unionid, nickname, avatar_url, phone_number,
                               phone_authorized, status, auth_version, cancelled_at
                        from app_user
                        where id = :userId
                        """)
                .param("userId", user.userId())
                .query()
                .singleRow();
        assertThat(remaining.get("OPENID")).isEqualTo(originalOpenid);
        assertThat(remaining.get("UNIONID")).isNull();
        assertThat(remaining.get("NICKNAME")).isEqualTo("");
        assertThat(remaining.get("AVATAR_URL")).isNull();
        assertThat(remaining.get("PHONE_NUMBER")).isNull();
        assertThat(remaining.get("PHONE_AUTHORIZED")).isEqualTo(false);
        assertThat(remaining.get("STATUS")).isEqualTo("ENABLED");
        assertThat(remaining.get("AUTH_VERSION")).isEqualTo(1L);
        assertThat(remaining.get("CANCELLED_AT")).isNull();

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", loginCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.userId").value(user.userId()))
                .andExpect(jsonPath("$.data.token", startsWith("app_")));
    }

    @Test
    void adminCanPageAndFilterRequests() throws Exception {
        AppSession user = loginApp("rights-admin-page");
        long requestId = submitRequest(user, "CORRECTION", "更正联系资料", null);
        String adminToken = loginAdmin();

        mockMvc.perform(get("/admin/account-rights/requests")
                        .param("userId", Long.toString(user.userId()))
                        .param("requestType", "CORRECTION")
                        .param("status", "PENDING")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(requestId)))
                .andExpect(jsonPath("$.data.records[0].userId").value(Long.toString(user.userId())));

        mockMvc.perform(get("/admin/account-rights/requests")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());

        mockMvc.perform(get("/admin/account-rights/requests")
                        .param("current", Long.toString(Long.MAX_VALUE))
                        .param("size", "100")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    private long submitRequest(
            AppSession user,
            String requestType,
            String requestNote,
            String wechatCode
    ) throws Exception {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestType", requestType);
        body.put("requestNote", requestNote);
        if (wechatCode != null) {
            body.put("wechatCode", wechatCode);
        }
        MvcResult result = mockMvc.perform(post("/app/account-rights/requests")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.userId").value(Long.toString(user.userId())))
                .andExpect(jsonPath("$.data.requestType").value(requestType))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        return read(result, "/data/id").asLong();
    }

    private void transition(
            String adminToken,
            long requestId,
            String action,
            long version
    ) throws Exception {
        mockMvc.perform(post("/admin/account-rights/requests/{requestId}/{action}", requestId, action)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transitionBody(version)))
                .andExpect(status().isOk());
    }

    private String transitionBody(long version) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "version", version,
                "reason", "已核验本次申请及处理动作",
                "retentionExplanation", "仅保留管理员在本次申请中明确记录的业务与审计资料",
                "retainedDataCategories", List.of("交易记录", "退款与审计记录")
        ));
    }

    private AppSession loginApp(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk())
                .andReturn();
        return new AppSession(
                read(result, "/data/token").asText(),
                read(result, "/data/refreshToken").asText(),
                read(result, "/data/user/userId").asLong()
        );
    }

    private String loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return read(result, "/data/token").asText();
    }

    private void insertOrder(long orderId, long userId, String status) {
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            receiver_name, receiver_phone, receiver_address)
                        values(
                            :id, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                            '测试用户', '13800138000', '测试地址')
                        """)
                .param("id", orderId)
                .param("orderNo", "RIGHTS" + orderId)
                .param("userId", userId)
                .param("status", status)
                .param("idempotencyKey", "rights-" + orderId)
                .update();
    }

    private JsonNode read(MvcResult result, String pointer) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at(pointer);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppSession(String accessToken, String refreshToken, long userId) {
    }
}
