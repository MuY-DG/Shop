package org.muybaby.shopserver.accountcancellation;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountCancellationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void appUserCancelsImmediatelyAndDisposableDataIsRemoved() throws Exception {
        String loginCode = "self-cancellation-complete";
        AppSession user = loginApp(loginCode);
        jdbcClient.sql("""
                        update app_user
                        set unionid = 'union-sensitive', nickname = '待注销用户',
                            avatar_url = 'https://example.test/avatar.png',
                            phone_number = '13812345678', phone_country_code = '86',
                            phone_authorized = true, phone_authorized_at = current_timestamp
                        where id = :userId
                        """)
                .param("userId", user.userId())
                .update();
        seedDisposableData(user.userId());

        mockMvc.perform(get("/app/account-cancellation/eligibility")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.activeOrderCount").value(0));

        Notice notice = currentNotice();
        mockMvc.perform(post("/app/account-cancellation")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancellationBody("another-wechat-user", notice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(120004));

        mockMvc.perform(post("/app/account-cancellation")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancellationBody(loginCode, notice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancellationId").isString())
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());

        Map<String, Object> cancelledUser = jdbcClient.sql("""
                        select openid, unionid, nickname, avatar_url, phone_number,
                               phone_authorized, status, auth_version, cancelled_at
                        from app_user where id = :userId
                        """)
                .param("userId", user.userId())
                .query()
                .singleRow();
        assertThat(cancelledUser.get("OPENID").toString()).startsWith("cancelled_");
        assertThat(cancelledUser.get("UNIONID")).isNull();
        assertThat(cancelledUser.get("NICKNAME")).isEqualTo("");
        assertThat(cancelledUser.get("AVATAR_URL")).isNull();
        assertThat(cancelledUser.get("PHONE_NUMBER")).isNull();
        assertThat(cancelledUser.get("PHONE_AUTHORIZED")).isEqualTo(false);
        assertThat(cancelledUser.get("STATUS")).isEqualTo("CANCELLED");
        assertThat(cancelledUser.get("AUTH_VERSION")).isEqualTo(1L);
        assertThat(cancelledUser.get("CANCELLED_AT")).isNotNull();

        assertThat(countForUser("app_user_account_cancellation", user.userId())).isEqualTo(1);
        assertThat(countForUser("cart_item", user.userId())).isZero();
        assertThat(countForUser("user_product_favorite", user.userId())).isZero();
        assertThat(countForUser("user_product_browse_history", user.userId())).isZero();
        assertThat(countForUser("user_address", user.userId())).isZero();
        assertThat(countForUser("user_coupon", user.userId())).isZero();
        assertThat(countForUser("coupon_claim_record", user.userId())).isZero();

        mockMvc.perform(get("/app/users/me")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/app/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized());

        AppSession replacement = loginApp(loginCode);
        assertThat(replacement.userId()).isNotEqualTo(user.userId());
    }

    @Test
    void activeOrderBlocksCancellationWithoutPartialChanges() throws Exception {
        String loginCode = "self-cancellation-active-order";
        AppSession user = loginApp(loginCode);
        insertOrder(9_105_001L, user.userId(), "PAID");
        Notice notice = currentNotice();

        mockMvc.perform(get("/app/account-cancellation/eligibility")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.activeOrderCount").value(1));

        mockMvc.perform(post("/app/account-cancellation")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancellationBody(loginCode, notice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(120005));

        assertThat(jdbcClient.sql("select status from app_user where id = :userId")
                .param("userId", user.userId())
                .query(String.class)
                .single()).isEqualTo("ENABLED");
        assertThat(countForUser("app_user_account_cancellation", user.userId())).isZero();
    }

    @Test
    void changedNoticeMustBeReadAgain() throws Exception {
        String loginCode = "self-cancellation-notice-change";
        AppSession user = loginApp(loginCode);
        Notice notice = currentNotice();

        mockMvc.perform(post("/app/account-cancellation")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancellationBody(loginCode,
                                new Notice(notice.version(), "0".repeat(64)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(120007));
    }

    private void seedDisposableData(long userId) {
        jdbcClient.sql("""
                        insert into cart_item(id, user_id, sku_id, quantity)
                        values(:id, :userId, 1, 1)
                        """)
                .param("id", userId + 10)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        insert into user_product_favorite(user_id, spu_id)
                        values(:userId, 1)
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        insert into user_product_browse_history(
                            user_id, spu_id, first_viewed_at, last_viewed_at)
                        values(:userId, 1, current_timestamp, current_timestamp)
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        insert into user_address(
                            id, user_id, receiver_name, receiver_phone,
                            province, city, district, detail_address)
                        values(
                            :id, :userId, '测试用户', '13800138000',
                            '广东省', '东莞市', '南城街道', '测试地址')
                        """)
                .param("id", userId + 20)
                .param("userId", userId)
                .update();
        long couponId = userId + 30;
        jdbcClient.sql("""
                        insert into user_coupon(
                            id, user_id, template_id, template_name, coupon_type,
                            discount_type, discount_cent, valid_start_at, valid_end_at, status)
                        values(
                            :id, :userId, 1, '注销测试券', 'MANUAL',
                            'AMOUNT_OFF', 100, current_timestamp,
                            dateadd('DAY', 7, current_timestamp), 'CLAIMED')
                        """)
                .param("id", couponId)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        insert into coupon_claim_record(id, template_id, user_id, user_coupon_id)
                        values(:id, 1, :userId, :couponId)
                        """)
                .param("id", couponId + 1)
                .param("userId", userId)
                .param("couponId", couponId)
                .update();
    }

    private void insertOrder(long orderId, long userId, String orderStatus) {
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                            receiver_name, receiver_phone, receiver_address)
                        values(
                            :id, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                            'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                            '测试用户', '13800138000', '测试地址')
                        """)
                .param("id", orderId)
                .param("orderNo", "CANCEL" + orderId)
                .param("userId", userId)
                .param("status", orderStatus)
                .param("idempotencyKey", "cancel-" + orderId)
                .update();
    }

    private int countForUser(String table, long userId) {
        return jdbcClient.sql("select count(*) from " + table + " where user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single();
    }

    private Notice currentNotice() {
        return jdbcClient.sql("""
                        select version, content_sha256
                        from legal_document_revision
                        where current_publication_key = 'ACCOUNT_CANCELLATION_NOTICE'
                        """)
                .query((rs, rowNum) -> new Notice(
                        rs.getString("version"), rs.getString("content_sha256")))
                .single();
    }

    private String cancellationBody(String wechatCode, Notice notice) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "wechatCode", wechatCode,
                "noticeVersion", notice.version(),
                "noticeContentSha256", notice.contentSha256(),
                "noticeAcknowledged", true,
                "miniProgramEnv", "develop"
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

    private JsonNode read(MvcResult result, String pointer) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).at(pointer);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppSession(String accessToken, String refreshToken, long userId) {
    }

    private record Notice(String version, String contentSha256) {
    }
}
