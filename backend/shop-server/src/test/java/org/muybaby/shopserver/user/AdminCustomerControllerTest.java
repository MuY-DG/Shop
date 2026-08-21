package org.muybaby.shopserver.user;

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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminCustomerControllerTest {

    private static final long CUSTOMER_ID = 9_123_456_789_012_345L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void pageSearchesCustomersAndReturnsCouponCountsWithoutWechatIdentityFields() throws Exception {
        seedCustomer(CUSTOMER_ID, "手动发券用户", "13800138000", "ENABLED");
        long templateId = seedTemplate("用户列表统计券", "ENABLED", 10, 0, 10,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
        seedUserCoupon(CUSTOMER_ID, templateId, "CLAIMED", LocalDateTime.now().plusDays(3));
        seedUserCoupon(CUSTOMER_ID, templateId, "USED", LocalDateTime.now().plusDays(3));
        seedUserCoupon(CUSTOMER_ID, templateId, "CLAIMED", LocalDateTime.now().minusDays(1));

        String token = tokenWith("customer:user:read");

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", Long.toString(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(CUSTOMER_ID)))
                .andExpect(jsonPath("$.data.records[0].nickname").value("手动发券用户"))
                .andExpect(jsonPath("$.data.records[0].phoneNumber").value("13800138000"))
                .andExpect(jsonPath("$.data.records[0].phoneAuthorized").value(true))
                .andExpect(jsonPath("$.data.records[0].couponTotalCount").value(3))
                .andExpect(jsonPath("$.data.records[0].couponAvailableCount").value(1))
                .andExpect(jsonPath("$.data.records[0].couponUsedCount").value(1))
                .andExpect(jsonPath("$.data.records[0].openid").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].unionid").doesNotExist());

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "手动发券"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.records[0].nickname").value("手动发券用户"));

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "13800138000")
                        .param("status", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.records[0].phoneNumber").value("13800138000"));
    }

    @Test
    void pageRequiresCustomerReadPermission() throws Exception {
        seedCustomer(CUSTOMER_ID, "无权查看用户", null, "ENABLED");

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + tokenWith()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + tokenWith("customer:coupon:issue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    void pageHidesCancelledCustomersByDefaultAndAllowsExplicitFilter() throws Exception {
        seedCustomer(CUSTOMER_ID, "", null, "CANCELLED");
        String token = tokenWith("customer:user:read");

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", Long.toString(CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/admin/customers")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", Long.toString(CUSTOMER_ID))
                        .param("status", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.records[0].nickname").value(""))
                .andExpect(jsonPath("$.data.records[0].phoneNumber").doesNotExist());
    }

    @Test
    void statusPermissionDisablesAndReenablesCustomerWithAuditAndSessionInvalidation() throws Exception {
        seedCustomer(CUSTOMER_ID, "状态管理用户", null, "ENABLED");
        String request = """
                {"status":"DISABLED","reason":"客服确认异常登录，临时停用"}
                """;

        mockMvc.perform(patch("/admin/customers/{userId}/status", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + tokenWith("customer:user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        String token = tokenWith("customer:user:status");
        mockMvc.perform(patch("/admin/customers/{userId}/status", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(Long.toString(CUSTOMER_ID)))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        assertThat(customerStatusAndVersion(CUSTOMER_ID)).isEqualTo("DISABLED|1");
        assertThat(latestStatusAudit(CUSTOMER_ID))
                .isEqualTo("ENABLED|DISABLED|客服确认异常登录，临时停用");

        mockMvc.perform(patch("/admin/customers/{userId}/status", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ENABLED","reason":"已完成身份核验，恢复使用"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        assertThat(customerStatusAndVersion(CUSTOMER_ID)).isEqualTo("ENABLED|2");
        assertThat(jdbcClient.sql("""
                        select count(*) from app_user_status_change_audit where user_id = :userId
                        """)
                .param("userId", CUSTOMER_ID)
                .query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void cancelledCustomerCannotBeReenabledByAdmin() throws Exception {
        seedCustomer(CUSTOMER_ID, "", null, "CANCELLED");

        mockMvc.perform(patch("/admin/customers/{userId}/status", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + tokenWith("customer:user:status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ENABLED","reason":"错误尝试恢复注销账号"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(100105));

        assertThat(customerStatusAndVersion(CUSTOMER_ID)).isEqualTo("CANCELLED|0");
    }

    @Test
    void issuableTemplatesExcludeInvalidStockValidityAndUserLimitCases() throws Exception {
        seedCustomer(CUSTOMER_ID, "优惠券候选用户", null, "ENABLED");
        LocalDateTime now = LocalDateTime.now();
        long eligibleId = seedTemplate("当前可发", "ENABLED", 5, 0, 2, now.minusDays(1), now.plusDays(5));
        seedTemplate("已禁用", "DISABLED", 5, 0, 2, now.minusDays(1), now.plusDays(5));
        seedTemplate("已过期", "ENABLED", 5, 0, 2, now.minusDays(5), now.minusDays(1));
        seedTemplate("无库存", "ENABLED", 5, 5, 2, now.minusDays(1), now.plusDays(5));
        long limitedId = seedTemplate("达到个人限领", "ENABLED", 5, 1, 1, now.minusDays(1), now.plusDays(5));
        seedUserCoupon(CUSTOMER_ID, limitedId, "CLAIMED", now.plusDays(5));

        mockMvc.perform(get("/admin/customers/{userId}/issuable-coupon-templates", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + tokenWith("customer:coupon:issue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(eligibleId))
                .andExpect(jsonPath("$.data[0].name").value("当前可发"))
                .andExpect(jsonPath("$.data[0].userClaimCount").value(0))
                .andExpect(jsonPath("$.data[0].perUserLimit").value(2));
    }

    @Test
    void issueCouponCreatesSnapshotConsumesStockAndWritesAdminAudit() throws Exception {
        seedCustomer(CUSTOMER_ID, "接券用户", "13900139000", "ENABLED");
        LocalDateTime now = LocalDateTime.now();
        long templateId = seedTemplate("客服补偿券", "ENABLED", 2, 0, 1,
                now.minusDays(1), now.plusDays(10));
        String token = tokenWith("customer:coupon:issue");

        String response = mockMvc.perform(post("/admin/customers/{userId}/coupons", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"note":"订单延迟补偿"}
                                """.formatted(templateId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userCouponId").isNumber())
                .andExpect(jsonPath("$.data.templateId").value(templateId))
                .andExpect(jsonPath("$.data.templateName").value("客服补偿券"))
                .andExpect(jsonPath("$.data.status").value("CLAIMED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long userCouponId = objectMapper.readTree(response).path("data").path("userCouponId").asLong();
        assertThat(jdbcClient.sql("select user_id from user_coupon where id = :id")
                .param("id", userCouponId)
                .query(Long.class)
                .single()).isEqualTo(CUSTOMER_ID);
        assertThat(jdbcClient.sql("select claimed_count from coupon_template where id = :id")
                .param("id", templateId)
                .query(Integer.class)
                .single()).isEqualTo(1);

        ClaimAudit audit = jdbcClient.sql("""
                        select issue_source, issued_by_admin_user_id, issue_note
                        from coupon_claim_record
                        where user_coupon_id = :userCouponId
                        """)
                .param("userCouponId", userCouponId)
                .query((rs, rowNum) -> new ClaimAudit(
                        rs.getString("issue_source"),
                        rs.getLong("issued_by_admin_user_id"),
                        rs.getString("issue_note")
                ))
                .single();
        assertThat(audit.issueSource()).isEqualTo("ADMIN_ISSUE");
        assertThat(audit.adminUserId()).isPositive();
        assertThat(audit.note()).isEqualTo("订单延迟补偿");

        mockMvc.perform(post("/admin/customers/{userId}/coupons", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"templateId":%d,"note":"重复发送"}
                                """.formatted(templateId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(300002));

        assertThat(jdbcClient.sql("select claimed_count from coupon_template where id = :id")
                .param("id", templateId)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void createDirectCouponBuildsExclusiveSnapshotAndExposesReadOnlyAdminTrace() throws Exception {
        seedCustomer(CUSTOMER_ID, "专属券用户", "13700137000", "ENABLED");
        OffsetDateTime validStartAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        OffsetDateTime validEndAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        String token = tokenWith("customer:coupon:issue");

        String response = mockMvc.perform(post("/admin/customers/{userId}/direct-coupons", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"专属补偿券","description":"仅此用户可用", "couponType":"MIN_SPEND",
                                 "thresholdCent":3000,"discountCent":800,
                                 "validStartAt":"%s","validEndAt":"%s","note":"专属售后补偿"}
                                """.formatted(validStartAt, validEndAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userCouponId").isNumber())
                .andExpect(jsonPath("$.data.templateName").value("专属补偿券"))
                .andExpect(jsonPath("$.data.status").value("CLAIMED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long templateId = objectMapper.readTree(response).path("data").path("templateId").asLong();
        long userCouponId = objectMapper.readTree(response).path("data").path("userCouponId").asLong();
        DirectTemplateState template = jdbcClient.sql("""
                        select distribution_mode, audience_user_id, total_stock, claimed_count, status
                        from coupon_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query((rs, rowNum) -> new DirectTemplateState(
                        rs.getString("distribution_mode"),
                        rs.getLong("audience_user_id"),
                        rs.getInt("total_stock"),
                        rs.getInt("claimed_count"),
                        rs.getString("status")
                ))
                .single();
        assertThat(template).isEqualTo(new DirectTemplateState("DIRECT", CUSTOMER_ID, 1, 1, "DISABLED"));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where id = :userCouponId
                          and user_id = :userId
                          and template_id = :templateId
                          and template_name = '专属补偿券'
                          and coupon_type = 'MIN_SPEND'
                          and discount_type = 'AMOUNT_OFF'
                          and threshold_cent = 3000
                          and discount_cent = 800
                          and scope_type = 'ALL'
                          and scope_value = ''
                          and status = 'CLAIMED'
                        """)
                .param("userCouponId", userCouponId)
                .param("userId", CUSTOMER_ID)
                .param("templateId", templateId)
                .query(Integer.class)
                .single()).isEqualTo(1);

        ClaimAudit audit = jdbcClient.sql("""
                        select issue_source, issued_by_admin_user_id, issue_note
                        from coupon_claim_record
                        where user_coupon_id = :userCouponId
                        """)
                .param("userCouponId", userCouponId)
                .query((rs, rowNum) -> new ClaimAudit(
                        rs.getString("issue_source"),
                        rs.getLong("issued_by_admin_user_id"),
                        rs.getString("issue_note")
                ))
                .single();
        assertThat(audit.issueSource()).isEqualTo("ADMIN_DIRECT");
        assertThat(audit.adminUserId()).isPositive();
        assertThat(audit.note()).isEqualTo("专属售后补偿");

        mockMvc.perform(get("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + token)
                        .param("name", "专属补偿券")
                        .param("distributionMode", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].distributionMode").value("DIRECT"))
                .andExpect(jsonPath("$.data.records[0].audienceUserId").value(CUSTOMER_ID));

        mockMvc.perform(post("/admin/marketing/coupons/templates/{templateId}/enable", templateId)
                        .header("Authorization", "Bearer " + tokenWith("coupon:template:enable")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(300001));
    }

    @Test
    void issueCouponRequiresPermissionAndEnabledCustomer() throws Exception {
        seedCustomer(CUSTOMER_ID, "停用用户", null, "DISABLED");
        long templateId = seedTemplate("不可发券", "ENABLED", 5, 0, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        String request = """
                {"templateId":%d,"note":"test"}
                """.formatted(templateId);

        mockMvc.perform(post("/admin/customers/{userId}/coupons", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + tokenWith("customer:user:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(post("/admin/customers/{userId}/coupons", CUSTOMER_ID)
                        .header("Authorization", "Bearer " + tokenWith("customer:coupon:issue"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100103))
                .andExpect(jsonPath("$.msg", containsString("App user")));
    }

    private void seedCustomer(long userId, String nickname, String phoneNumber, String status) {
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, nickname, phone_number, phone_country_code, phone_authorized,
                             status, last_login_at, created_at, updated_at)
                        values
                            (:id, :openid, :nickname, :phoneNumber, :countryCode, :phoneAuthorized,
                             :status, :now, :now, :now)
                        """)
                .param("id", userId)
                .param("openid", "admin-customer-openid-" + userId)
                .param("nickname", nickname)
                .param("phoneNumber", phoneNumber)
                .param("countryCode", phoneNumber == null ? null : "86")
                .param("phoneAuthorized", phoneNumber != null)
                .param("status", status)
                .param("now", LocalDateTime.now())
                .update();
    }

    private long seedTemplate(
            String name,
            String status,
            int totalStock,
            int claimedCount,
            int perUserLimit,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt
    ) {
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'admin issue test', 'MIN_SPEND', 'AMOUNT_OFF', 2000, 500,
                             'ALL', '', 'coupon.amount-off.v1', :totalStock, :claimedCount, :perUserLimit,
                             :validStartAt, :validEndAt, :status, 1)
                        """)
                .param("name", name)
                .param("totalStock", totalStock)
                .param("claimedCount", claimedCount)
                .param("perUserLimit", perUserLimit)
                .param("validStartAt", validStartAt)
                .param("validEndAt", validEndAt)
                .param("status", status)
                .update();
        return jdbcClient.sql("select id from coupon_template where name = :name order by id desc limit 1")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void seedUserCoupon(long userId, long templateId, String status, LocalDateTime validEndAt) {
        jdbcClient.sql("""
                        insert into user_coupon
                            (user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value,
                             valid_start_at, valid_end_at, status, claimed_at)
                        values
                            (:userId, :templateId, '统计券', 'MIN_SPEND', 'AMOUNT_OFF',
                             2000, 500, 'ALL', '', :validStartAt, :validEndAt, :status, :claimedAt)
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("validStartAt", LocalDateTime.now().minusDays(2))
                .param("validEndAt", validEndAt)
                .param("status", status)
                .param("claimedAt", LocalDateTime.now().minusHours(1))
                .update();
    }

    private String tokenWith(String... permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of(permissions)
        );
    }

    private String customerStatusAndVersion(long userId) {
        return jdbcClient.sql("""
                        select concat(status, '|', auth_version) from app_user where id = :userId
                        """)
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    private String latestStatusAudit(long userId) {
        return jdbcClient.sql("""
                        select concat(from_status, '|', to_status, '|', reason)
                        from app_user_status_change_audit
                        where user_id = :userId
                        order by created_at desc, id desc
                        limit 1
                        """)
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    private record ClaimAudit(String issueSource, Long adminUserId, String note) {
    }

    private record DirectTemplateState(
            String distributionMode,
            Long audienceUserId,
            Integer totalStock,
            Integer claimedCount,
            String status
    ) {
    }
}
