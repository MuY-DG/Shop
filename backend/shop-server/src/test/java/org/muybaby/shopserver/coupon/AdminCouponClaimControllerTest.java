package org.muybaby.shopserver.coupon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminCouponClaimControllerTest {

    private static final long USER_ID = 993_201L;
    private static final long PUBLIC_TEMPLATE_ID = 993_211L;
    private static final long DIRECT_TEMPLATE_ID = 993_212L;
    private static final long SELF_USER_COUPON_ID = 993_221L;
    private static final long ADMIN_USER_COUPON_ID = 993_222L;
    private static final long DIRECT_USER_COUPON_ID = 993_223L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void seedClaimRecords() {
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, nickname, phone_number, phone_authorized, status)
                        values
                            (:userId, :openid, '领取记录用户', '13800138201', true, 'ENABLED')
                        """)
                .param("userId", USER_ID)
                .param("openid", "coupon-claim-record-" + USER_ID)
                .update();
        insertTemplate(PUBLIC_TEMPLATE_ID, "公开测试券", "PUBLIC", null, 2, 2, "ENABLED");
        insertTemplate(DIRECT_TEMPLATE_ID, "专属测试券", "DIRECT", USER_ID, 1, 1, "DISABLED");
        insertUserCoupon(SELF_USER_COUPON_ID, PUBLIC_TEMPLATE_ID, "公开测试券", "CLAIMED", null, null);
        insertUserCoupon(
                ADMIN_USER_COUPON_ID,
                PUBLIC_TEMPLATE_ID,
                "公开测试券",
                "USED",
                88001L,
                LocalDateTime.of(2026, 7, 15, 10, 30)
        );
        insertUserCoupon(DIRECT_USER_COUPON_ID, DIRECT_TEMPLATE_ID, "专属测试券", "CLAIMED", null, null);
        insertClaimRecord(
                993_231L,
                PUBLIC_TEMPLATE_ID,
                SELF_USER_COUPON_ID,
                "SELF_CLAIM",
                null,
                "",
                LocalDateTime.of(2026, 7, 15, 8, 0)
        );
        insertClaimRecord(
                993_232L,
                PUBLIC_TEMPLATE_ID,
                ADMIN_USER_COUPON_ID,
                "ADMIN_ISSUE",
                1L,
                "老客补偿",
                LocalDateTime.of(2026, 7, 15, 9, 0)
        );
        insertClaimRecord(
                993_233L,
                DIRECT_TEMPLATE_ID,
                DIRECT_USER_COUPON_ID,
                "ADMIN_DIRECT",
                1L,
                "专属补偿",
                LocalDateTime.of(2026, 7, 15, 10, 0)
        );
    }

    @Test
    void claimRecordPageShowsPublicAndDirectSourcesWithUserAndOperator() throws Exception {
        String token = tokenWithPermissions(List.of("coupon:claim:read"));

        mockMvc.perform(get("/admin/marketing/coupons/claims")
                        .param("userKeyword", "13800138201")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records[0].templateName").value("专属测试券"))
                .andExpect(jsonPath("$.data.records[0].distributionMode").value("DIRECT"))
                .andExpect(jsonPath("$.data.records[0].issueSource").value("ADMIN_DIRECT"))
                .andExpect(jsonPath("$.data.records[0].userId").value(Long.toString(USER_ID)))
                .andExpect(jsonPath("$.data.records[0].userNickname").value("领取记录用户"))
                .andExpect(jsonPath("$.data.records[0].operatorDisplayName").value("Super Admin"))
                .andExpect(jsonPath("$.data.records[0].issueNote").value("专属补偿"))
                .andExpect(jsonPath("$.data.records[1].status").value("USED"))
                .andExpect(jsonPath("$.data.records[1].usedOrderId").value(88001));
    }

    @Test
    void claimRecordPageSupportsSourceDistributionStatusAndUserFilters() throws Exception {
        String token = tokenWithPermissions(List.of("coupon:claim:read"));

        mockMvc.perform(get("/admin/marketing/coupons/claims")
                        .param("issueSource", "ADMIN_DIRECT")
                        .param("distributionMode", "DIRECT")
                        .param("status", "CLAIMED")
                        .param("templateName", "专属")
                        .param("userKeyword", "13800138201")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].userCouponId").value(DIRECT_USER_COUPON_ID));
    }

    @Test
    void claimRecordPageRequiresDedicatedReadPermission() throws Exception {
        String unrelatedToken = tokenWithPermissions(List.of("coupon:template:create"));

        mockMvc.perform(get("/admin/marketing/coupons/claims")
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
    }

    private String tokenWithPermissions(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }

    private void insertTemplate(
            long id,
            String name,
            String distributionMode,
            Long audienceUserId,
            int totalStock,
            int claimedCount,
            String status
    ) {
        jdbcClient.sql("""
                        insert into coupon_template
                            (id, name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order,
                             distribution_mode, audience_user_id)
                        values
                            (:id, :name, '', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, 300,
                             'ALL', '', 'coupon.amount-off.v1', :totalStock, :claimedCount, 1,
                             timestamp '2026-07-01 00:00:00', timestamp '2026-08-01 00:00:00', :status, 0,
                             :distributionMode, :audienceUserId)
                        """)
                .param("id", id)
                .param("name", name)
                .param("totalStock", totalStock)
                .param("claimedCount", claimedCount)
                .param("status", status)
                .param("distributionMode", distributionMode)
                .param("audienceUserId", audienceUserId)
                .update();
    }

    private void insertUserCoupon(
            long id,
            long templateId,
            String templateName,
            String status,
            Long usedOrderId,
            LocalDateTime usedAt
    ) {
        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value,
                             valid_start_at, valid_end_at, status, claimed_at, used_order_id, used_at)
                        values
                            (:id, :userId, :templateId, :templateName, 'NO_THRESHOLD', 'AMOUNT_OFF',
                             0, 300, 'ALL', '', timestamp '2026-07-01 00:00:00', timestamp '2026-08-01 00:00:00',
                             :status, timestamp '2026-07-15 08:00:00', :usedOrderId, :usedAt)
                        """)
                .param("id", id)
                .param("userId", USER_ID)
                .param("templateId", templateId)
                .param("templateName", templateName)
                .param("status", status)
                .param("usedOrderId", usedOrderId)
                .param("usedAt", usedAt)
                .update();
    }

    private void insertClaimRecord(
            long id,
            long templateId,
            long userCouponId,
            String issueSource,
            Long operatorId,
            String note,
            LocalDateTime claimedAt
    ) {
        jdbcClient.sql("""
                        insert into coupon_claim_record
                            (id, template_id, user_id, user_coupon_id, claimed_at,
                             issue_source, issued_by_admin_user_id, issue_note)
                        values
                            (:id, :templateId, :userId, :userCouponId, :claimedAt,
                             :issueSource, :operatorId, :note)
                        """)
                .param("id", id)
                .param("templateId", templateId)
                .param("userId", USER_ID)
                .param("userCouponId", userCouponId)
                .param("claimedAt", claimedAt)
                .param("issueSource", issueSource)
                .param("operatorId", operatorId)
                .param("note", note)
                .update();
    }
}
