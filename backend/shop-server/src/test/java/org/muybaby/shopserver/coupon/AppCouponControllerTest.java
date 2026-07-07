package org.muybaby.shopserver.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppCouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminProductService adminProductService;

    @BeforeEach
    void clearCouponState() {
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from cart_item").update();
    }

    @Test
    void couponApisRequireAppToken() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(get("/app/coupons/claimable"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/app/coupons/available")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    @Test
    void claimableListReturnsEnabledCurrentTemplatesAndClaimableFlag() throws Exception {
        AppLoginSession session = appLogin("coupon-claimable-user");
        String appToken = session.token();
        seedTemplate("Claimable Coupon", "ENABLED", 10, 0, 1);
        long exhaustedTemplateId = seedTemplate("Exhausted Coupon", "ENABLED", 1, 0, 1);
        seedTemplate("Disabled Coupon", "DISABLED", 10, 0, 1);
        seedExpiredTemplate("Expired Coupon");
        seedUserCoupon(session.userId(), exhaustedTemplateId, "Exhausted Coupon", "CLAIMED");

        mockMvc.perform(get("/app/coupons/claimable")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Claimable Coupon"))
                .andExpect(jsonPath("$.data[0].claimable").value(true))
                .andExpect(jsonPath("$.data[1].name").value("Exhausted Coupon"))
                .andExpect(jsonPath("$.data[1].claimable").value(false))
                .andExpect(jsonPath("$.data[1].unavailableReason").value("CLAIM_LIMIT_REACHED"));
    }

    @Test
    void claimCreatesUserCouponSnapshotAndClaimRecord() throws Exception {
        String appToken = appLoginAndExtractToken("coupon-claim-user");
        long templateId = seedTemplate("Claim Coupon", "ENABLED", 10, 0, 2);

        String response = mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", templateId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templateId").value(templateId))
                .andExpect(jsonPath("$.data.name").value("Claim Coupon"))
                .andExpect(jsonPath("$.data.status").value("CLAIMED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long userCouponId = objectMapper.readTree(response).path("data").path("userCouponId").asLong();
        Integer claimedCount = jdbcClient.sql("select claimed_count from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
        Integer userCouponRows = jdbcClient.sql("select count(*) from user_coupon where id = :userCouponId and template_id = :templateId")
                .param("userCouponId", userCouponId)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
        Integer claimRecordRows = jdbcClient.sql("select count(*) from coupon_claim_record where user_coupon_id = :userCouponId and template_id = :templateId")
                .param("userCouponId", userCouponId)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();

        assertThat(claimedCount).isEqualTo(1);
        assertThat(userCouponRows).isEqualTo(1);
        assertThat(claimRecordRows).isEqualTo(1);
    }

    @Test
    void claimRejectsOverPerUserLimit() throws Exception {
        AppLoginSession session = appLogin("coupon-limit-user");
        String appToken = session.token();
        long userId = session.userId();
        long templateId = seedTemplate("Limit Coupon", "ENABLED", 10, 0, 1);
        seedUserCoupon(userId, templateId, "Limit Coupon", "CLAIMED");

        mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", templateId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(300002));
    }

    @Test
    void claimRejectsUnavailableTemplate() throws Exception {
        String appToken = appLoginAndExtractToken("coupon-unavailable-user");
        long disabledTemplateId = seedTemplate("Disabled Coupon", "DISABLED", 10, 0, 1);
        long exhaustedTemplateId = seedTemplate("Exhausted Coupon", "ENABLED", 1, 1, 1);

        mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", disabledTemplateId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(300001));

        mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", exhaustedTemplateId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(300001));
    }

    @Test
    void mineReturnsCurrentUserCouponSnapshotsOrderedByClaimedAtDesc() throws Exception {
        AppLoginSession session = appLogin("coupon-mine-user");
        String appToken = session.token();
        long userId = session.userId();
        long templateId = seedTemplate("Mine Coupon", "ENABLED", 10, 0, 2);
        seedUserCoupon(userId, templateId, "Older Coupon", "CLAIMED", "2026-07-07 08:00:00");
        seedUserCoupon(userId, templateId, "Newest Coupon", "USED", "2026-07-07 09:00:00");

        mockMvc.perform(get("/app/coupons/mine?status=CLAIMED")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Older Coupon"))
                .andExpect(jsonPath("$.data[0].status").value("CLAIMED"));

        mockMvc.perform(get("/app/coupons/mine")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("Newest Coupon"))
                .andExpect(jsonPath("$.data[1].name").value("Older Coupon"));
    }

    @Test
    void availableCouponsUseCurrentCartAmountAndReturnBestDiscount() throws Exception {
        AppLoginSession session = appLogin("coupon-available-user");
        String appToken = session.token();
        long userId = session.userId();
        long skuId = createPublishedSku("COUPON-AVAILABLE-SKU", 3990L, 4990L, 10, "ENABLED");
        long templateA = seedTemplate("Five Off", "ENABLED", 10, 0, 2, 0L, 500L);
        long templateB = seedTemplate("Ten Off Threshold", "ENABLED", 10, 0, 2, 7000L, 1000L);

        long userCouponA = seedUserCoupon(userId, templateA, "Five Off", "CLAIMED", "2026-07-07 08:00:00");
        long userCouponB = seedUserCoupon(userId, templateB, "Ten Off Threshold", "CLAIMED", "2026-07-07 09:00:00");

        String cartResponse = mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long cartItemId = objectMapper.readTree(cartResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/app/coupons/available")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartItemIds":[%d]}
                                """.formatted(cartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartAmountCent").value(7980))
                .andExpect(jsonPath("$.data.bestUserCouponId").value(userCouponB))
                .andExpect(jsonPath("$.data.bestDiscountCent").value(1000))
                .andExpect(jsonPath("$.data.payableAmountCent").value(6980))
                .andExpect(jsonPath("$.data.coupons.length()").value(2))
                .andExpect(jsonPath("$.data.coupons[0].userCouponId").value(userCouponB))
                .andExpect(jsonPath("$.data.coupons[0].discountAmountCent").value(1000))
                .andExpect(jsonPath("$.data.coupons[1].userCouponId").value(userCouponA))
                .andExpect(jsonPath("$.data.coupons[1].discountAmountCent").value(500));
    }

    private String appLoginAndExtractToken(String code) throws Exception {
        return appLogin(code).token();
    }

    private AppLoginSession appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new AppLoginSession(
                objectMapper.readTree(response).path("data").path("token").asText(),
                objectMapper.readTree(response).path("data").path("user").path("userId").asLong()
        );
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long createPublishedSku(String skuCode, long priceCent, long originalPriceCent, int stock, String skuStatus) {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Coupon Category " + skuCode, "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Coupon SPU " + skuCode,
                "Coupon subtitle",
                "https://example.test/coupon-main.jpg",
                "辣香浓郁,适合下单",
                "<p>Coupon detail</p>",
                1,
                List.of("https://example.test/coupon-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, skuCode, "{\"规格\":\"300g\"}", "300g", priceCent, originalPriceCent, stock, 300, "https://example.test/coupon-sku.jpg", skuStatus, 1))
        ));
        adminProductService.publishSpu(spuId);
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
    }

    private long seedTemplate(String name, String status, int totalStock, int claimedCount, int perUserLimit) {
        return seedTemplate(name, status, totalStock, claimedCount, perUserLimit, 0L, 500L);
    }

    private long seedTemplate(String name, String status, int totalStock, int claimedCount, int perUserLimit, long thresholdCent, long discountCent) {
        String couponType = thresholdCent == 0L ? "NO_THRESHOLD" : "MIN_SPEND";
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'seed', :couponType, 'AMOUNT_OFF', :thresholdCent, :discountCent,
                             'ALL', '', 'coupon.amount-off.v1', :totalStock, :claimedCount, :perUserLimit,
                             timestamp '2026-07-01 00:00:00', timestamp '2026-08-01 23:59:59', :status, 1)
                        """)
                .param("name", name)
                .param("couponType", couponType)
                .param("thresholdCent", thresholdCent)
                .param("discountCent", discountCent)
                .param("totalStock", totalStock)
                .param("claimedCount", claimedCount)
                .param("perUserLimit", perUserLimit)
                .param("status", status)
                .update();
        return jdbcClient.sql("select id from coupon_template where name = :name order by id desc limit 1")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void seedExpiredTemplate(String name) {
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'seed', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, 500,
                             'ALL', '', 'coupon.amount-off.v1', 10, 0, 1,
                             timestamp '2026-05-01 00:00:00', timestamp '2026-05-31 23:59:59', 'ENABLED', 1)
                        """)
                .param("name", name)
                .update();
    }

    private long seedUserCoupon(long userId, long templateId, String templateName, String status) {
        return seedUserCoupon(userId, templateId, templateName, status, "2026-07-07 08:00:00");
    }

    private long seedUserCoupon(long userId, long templateId, String templateName, String status, String claimedAt) {
        String couponType = jdbcClient.sql("select coupon_type from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(String.class)
                .single();
        Long thresholdCent = jdbcClient.sql("select threshold_cent from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(Long.class)
                .single();
        Long discountCent = jdbcClient.sql("select discount_cent from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into user_coupon
                            (user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value, valid_start_at,
                             valid_end_at, status, claimed_at)
                        values
                            (:userId, :templateId, :templateName, :couponType, 'AMOUNT_OFF',
                             :thresholdCent, :discountCent, 'ALL', '', timestamp '2026-07-01 00:00:00',
                             timestamp '2026-08-01 23:59:59', :status, :claimedAt)
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("templateName", templateName)
                .param("couponType", couponType)
                .param("thresholdCent", thresholdCent)
                .param("discountCent", discountCent)
                .param("status", status)
                .param("claimedAt", LocalDateTime.parse(claimedAt.replace(" ", "T")))
                .update();
        return jdbcClient.sql("""
                        select id
                        from user_coupon
                        where user_id = :userId and template_id = :templateId and template_name = :templateName
                        order by id desc
                        limit 1
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("templateName", templateName)
                .query(Long.class)
                .single();
    }

    private record AppLoginSession(String token, long userId) {
    }
}
