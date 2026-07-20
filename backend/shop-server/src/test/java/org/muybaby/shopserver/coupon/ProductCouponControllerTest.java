package org.muybaby.shopserver.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProductCouponControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void productCouponCreationForcesPathScopeAndAutomaticallyBinds() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long spuId = seedProduct("Coupon draft", "DRAFT", 500L);

        long templateId = createProductCoupon(adminToken, spuId, "Path product coupon", "CATEGORY", "999999");

        String scope = jdbcClient.sql("""
                        select concat(scope_type, ':', scope_value)
                        from coupon_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query(String.class)
                .single();
        Integer bindingCount = jdbcClient.sql("""
                        select count(*)
                        from product_spu_coupon
                        where spu_id = :spuId and coupon_template_id = :templateId
                        """)
                .param("spuId", spuId)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
        assertThat(scope).isEqualTo("PRODUCT:" + spuId);
        assertThat(bindingCount).isEqualTo(1);

        mockMvc.perform(get("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(templateId))
                .andExpect(jsonPath("$.data[0].scopeType").value("PRODUCT"))
                .andExpect(jsonPath("$.data[0].scopeValue").value(Long.toString(spuId)));

        String genericCreateResponse = mockMvc.perform(post("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Generic product coupon", "PRODUCT", Long.toString(spuId))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long genericTemplateId = objectMapper.readTree(genericCreateResponse).path("data").asLong();
        assertThat(boundTemplateIds(spuId)).contains(templateId, genericTemplateId);

        mockMvc.perform(put("/admin/marketing/coupons/templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Generic product update", "PRODUCT", Long.toString(spuId))))
                .andExpect(status().isOk());
        String updatedName = jdbcClient.sql("select name from coupon_template where id = :templateId")
                .param("templateId", templateId)
                .query(String.class)
                .single();
        assertThat(updatedName).isEqualTo("Generic product update");

        mockMvc.perform(put("/admin/marketing/coupons/templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Scope transition", "ALL", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(post("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Missing product coupon", "PRODUCT", "999999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        mockMvc.perform(post("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Category unsupported", "CATEGORY", Long.toString(spuId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    @Test
    void bindingsAllowAllAndMatchingProductCouponsButRejectOtherProductScope() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long spuId = seedProduct("Binding target", "DRAFT", 500L);
        long otherSpuId = seedProduct("Binding other", "DRAFT", 500L);
        long allTemplateId = seedTemplate("Bound all", "ALL", "", "ENABLED", 0L, 100L);
        long productTemplateId = seedTemplate(
                "Bound product",
                "PRODUCT",
                Long.toString(spuId),
                "ENABLED",
                0L,
                100L
        );
        long otherProductTemplateId = seedTemplate(
                "Other product",
                "PRODUCT",
                Long.toString(otherSpuId),
                "ENABLED",
                0L,
                100L
        );

        mockMvc.perform(put("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponTemplateIds\":[%d,%d]}".formatted(allTemplateId, productTemplateId)))
                .andExpect(status().isOk());
        assertThat(boundTemplateIds(spuId)).containsExactlyInAnyOrder(allTemplateId, productTemplateId);

        mockMvc.perform(put("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponTemplateIds\":[]}"))
                .andExpect(status().isOk());
        assertThat(boundTemplateIds(spuId)).containsExactly(productTemplateId);

        mockMvc.perform(put("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponTemplateIds\":[%d]}".formatted(otherProductTemplateId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
        assertThat(boundTemplateIds(spuId)).containsExactly(productTemplateId);
    }

    @Test
    void aggregateSpuUpdateCannotChangeCouponBindingsWithoutDedicatedBindAuthority() throws Exception {
        long spuId = seedProduct("Aggregate coupon boundary", "DRAFT", 500L);
        long categoryId = jdbcClient.sql("select category_id from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        long existingTemplateId = seedTemplate("Existing aggregate binding", "ALL", "", "ENABLED", 0L, 100L);
        long replacementTemplateId = seedTemplate("Replacement aggregate binding", "ALL", "", "ENABLED", 0L, 100L);
        bind(spuId, existingTemplateId);

        String spuUpdateToken = limitedAdminToken(List.of("product:spu:update"));
        String couponBindToken = limitedAdminToken(List.of("product:coupon:bind"));

        mockMvc.perform(put("/admin/product/spus/{spuId}", spuId)
                        .header("Authorization", "Bearer " + spuUpdateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Aggregate coupon boundary updated",
                                  "mainImage": "https://example.com/aggregate-coupon.png",
                                  "sortOrder": 0,
                                  "couponTemplateIds": [%d]
                                }
                                """.formatted(categoryId, replacementTemplateId)))
                .andExpect(status().isOk());
        assertThat(boundTemplateIds(spuId)).containsExactly(existingTemplateId);

        mockMvc.perform(put("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + couponBindToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponTemplateIds\":[%d]}".formatted(replacementTemplateId)))
                .andExpect(status().isOk());
        assertThat(boundTemplateIds(spuId)).containsExactly(replacementTemplateId);
    }

    @Test
    void productCouponEndpointsEnforceReadBindAndCreateAuthorities() throws Exception {
        long spuId = seedProduct("Authority target", "DRAFT", 500L);
        String unrelatedToken = limitedAdminToken(List.of("product:sku:stock"));
        String bindToken = limitedAdminToken(List.of("product:coupon:bind"));
        String createToken = limitedAdminToken(List.of("product:coupon:create"));

        mockMvc.perform(get("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        mockMvc.perform(get("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + bindToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponTemplateIds\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + bindToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"couponTemplateIds\":[]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + bindToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Forbidden create", "ALL", "")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest("Allowed create", "ALL", "")))
                .andExpect(status().isOk());
    }

    @Test
    void appProductCouponCanBeListedClaimedAndAppliedOnlyToMatchingSpuAmount() throws Exception {
        ProductRow matching = seedProductWithSku("Matching product", 300L);
        ProductRow other = seedProductWithSku("Other product", 700L);
        long productTemplateId = seedTemplate(
                "Matching product coupon",
                "PRODUCT",
                Long.toString(matching.spuId()),
                "ENABLED",
                0L,
                500L
        );
        long allTemplateId = seedTemplate("Presented all coupon", "ALL", "", "ENABLED", 0L, 100L);
        seedTemplate("Unbound all coupon", "ALL", "", "ENABLED", 0L, 100L);
        long otherProductTemplateId = seedTemplate(
                "Other product coupon",
                "PRODUCT",
                Long.toString(other.spuId()),
                "ENABLED",
                0L,
                500L
        );
        long categoryTemplateId = seedTemplate(
                "Unsupported category coupon",
                "CATEGORY",
                "1",
                "ENABLED",
                0L,
                100L
        );
        bind(matching.spuId(), productTemplateId);
        bind(matching.spuId(), allTemplateId);
        bind(other.spuId(), otherProductTemplateId);

        AppLoginSession session = appLogin("product-coupon-user");

        mockMvc.perform(get("/app/product/spus/{spuId}/coupons", matching.spuId())
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.name == 'Matching product coupon')]").exists())
                .andExpect(jsonPath("$.data[?(@.name == 'Presented all coupon')]").exists());

        String generalClaimable = mockMvc.perform(get("/app/coupons/claimable")
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(generalClaimable).path("data").toString())
                .doesNotContain("Matching product coupon")
                .doesNotContain("Other product coupon")
                .contains("Presented all coupon", "Unbound all coupon");

        String claimResponse = mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", productTemplateId)
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeType").value("PRODUCT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long userCouponId = objectMapper.readTree(claimResponse).path("data").path("userCouponId").asLong();

        mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", categoryTemplateId)
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.COUPON_UNAVAILABLE.code()));

        long matchingCartItemId = seedCartItem(session.userId(), matching.skuId(), 1);
        long otherCartItemId = seedCartItem(session.userId(), other.skuId(), 1);

        mockMvc.perform(post("/app/coupons/available")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[%d,%d]}".formatted(matchingCartItemId, otherCartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartAmountCent").value(1000))
                .andExpect(jsonPath("$.data.bestUserCouponId").value(userCouponId))
                .andExpect(jsonPath("$.data.bestDiscountCent").value(300))
                .andExpect(jsonPath("$.data.payableAmountCent").value(700));

        mockMvc.perform(post("/app/coupons/available")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[%d]}".formatted(otherCartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bestUserCouponId").doesNotExist())
                .andExpect(jsonPath("$.data.bestDiscountCent").value(0))
                .andExpect(jsonPath("$.data.payableAmountCent").value(700))
                .andExpect(jsonPath("$.data.coupons[0].available").value(false))
                .andExpect(jsonPath("$.data.coupons[0].unavailableReason").value("SCOPE_NOT_APPLICABLE"));

        jdbcClient.sql("""
                        update product_spu
                        set status = 'OFF_SALE', deleted_at = current_timestamp
                        where id = :spuId
                        """)
                .param("spuId", matching.spuId())
                .update();
        mockMvc.perform(post("/app/coupons/available")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[%d,%d]}".formatted(matchingCartItemId, otherCartItemId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartAmountCent").value(700))
                .andExpect(jsonPath("$.data.bestUserCouponId").doesNotExist())
                .andExpect(jsonPath("$.data.bestDiscountCent").value(0))
                .andExpect(jsonPath("$.data.payableAmountCent").value(700));

        AppLoginSession secondSession = appLogin("recycled-product-coupon-user");
        mockMvc.perform(post("/app/coupons/templates/{templateId}/claim", productTemplateId)
                        .header("Authorization", "Bearer " + secondSession.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.COUPON_UNAVAILABLE.code()));
    }

    private long createProductCoupon(
            String token,
            long spuId,
            String name,
            String requestedScopeType,
            String requestedScopeValue
    ) throws Exception {
        String response = mockMvc.perform(post("/admin/product/spus/{spuId}/coupons", spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(couponRequest(name, requestedScopeType, requestedScopeValue)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String couponRequest(String name, String scopeType, String scopeValue) {
        LocalDateTime now = LocalDateTime.now();
        return """
                {"name":"%s","description":"product coupon","couponType":"NO_THRESHOLD","discountType":"AMOUNT_OFF",
                 "thresholdCent":0,"discountCent":500,"scopeType":"%s","scopeValue":"%s",
                 "strategyKey":"coupon.amount-off.v1","totalStock":100,"perUserLimit":1,
                 "validStartAt":"%s","validEndAt":"%s",
                 "status":"ENABLED","sortOrder":1}
                """.formatted(name, scopeType, scopeValue, now.minusDays(1), now.plusDays(30));
    }

    private long seedProduct(String title, String status, long priceCent) {
        long categoryId = seedCategory("Category " + title);
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values
                            (:categoryId, :title, '', '', '', '', 0, :status)
                        """)
                .param("categoryId", categoryId)
                .param("title", title)
                .param("status", status)
                .update();
        return jdbcClient.sql("select id from product_spu where title = :title order by id desc limit 1")
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private ProductRow seedProductWithSku(String title, long priceCent) {
        long spuId = seedProduct(title, "ON_SALE", priceCent);
        String skuCode = "COUPON-" + spuId;
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order, is_default, combination_key)
                        values
                            (:spuId, :skuCode, '{}', '默认', :priceCent, :priceCent,
                             100, 0, '', 'ENABLED', 0, true, :combinationKey)
                        """)
                .param("spuId", spuId)
                .param("skuCode", skuCode)
                .param("priceCent", priceCent)
                .param("combinationKey", "default-" + spuId)
                .update();
        long skuId = jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
        return new ProductRow(spuId, skuId);
    }

    private long seedCategory(String name) {
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 0, 'ENABLED')
                        """)
                .param("name", name)
                .update();
        return jdbcClient.sql("select id from product_category where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long seedTemplate(
            String name,
            String scopeType,
            String scopeValue,
            String status,
            long thresholdCent,
            long discountCent
    ) {
        String couponType = thresholdCent == 0L ? "NO_THRESHOLD" : "MIN_SPEND";
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'seed', :couponType, 'AMOUNT_OFF', :thresholdCent, :discountCent,
                             :scopeType, :scopeValue, 'coupon.amount-off.v1', 100, 0, 1,
                             :validStartAt, :validEndAt, :status, 1)
                        """)
                .param("name", name)
                .param("couponType", couponType)
                .param("thresholdCent", thresholdCent)
                .param("discountCent", discountCent)
                .param("scopeType", scopeType)
                .param("scopeValue", scopeValue)
                .param("validStartAt", now.minusDays(1))
                .param("validEndAt", now.plusDays(30))
                .param("status", status)
                .update();
        return jdbcClient.sql("select id from coupon_template where name = :name order by id desc limit 1")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void bind(long spuId, long templateId) {
        jdbcClient.sql("""
                        insert into product_spu_coupon (spu_id, coupon_template_id)
                        values (:spuId, :templateId)
                        """)
                .param("spuId", spuId)
                .param("templateId", templateId)
                .update();
    }

    private List<Long> boundTemplateIds(long spuId) {
        return jdbcClient.sql("""
                        select coupon_template_id
                        from product_spu_coupon
                        where spu_id = :spuId
                        order by coupon_template_id
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list();
    }

    private long seedCartItem(long userId, long skuId, int quantity) {
        jdbcClient.sql("""
                        insert into cart_item (user_id, sku_id, quantity)
                        values (:userId, :skuId, :quantity)
                        """)
                .param("userId", userId)
                .param("skuId", skuId)
                .param("quantity", quantity)
                .update();
        return jdbcClient.sql("select id from cart_item where user_id = :userId and sku_id = :skuId")
                .param("userId", userId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"Super\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private AppLoginSession appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(response).path("data");
        return new AppLoginSession(
                data.path("token").asText(),
                data.path("user").path("userId").asLong()
        );
    }

    private String limitedAdminToken(List<String> permissions) {
        return issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }

    private record ProductRow(long spuId, long skuId) {
    }

    private record AppLoginSession(String token, long userId) {
    }
}
