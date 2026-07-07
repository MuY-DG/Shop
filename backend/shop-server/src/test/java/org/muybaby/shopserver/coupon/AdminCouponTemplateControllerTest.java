package org.muybaby.shopserver.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminCouponTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminTokenSeparationAndCouponTemplateCrudFlow() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(get("/admin/marketing/coupons/templates"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTemplateJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        String createResponse = mockMvc.perform(post("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validTemplateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long templateId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(get("/admin/marketing/coupons/templates?current=1&size=20&name=新人")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].stockRemaining").value(100));

        mockMvc.perform(put("/admin/marketing/coupons/templates/" + templateId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新人满减券更新","description":"update","couponType":"MIN_SPEND","discountType":"AMOUNT_OFF",
                                 "thresholdCent":3000,"discountCent":500,"scopeType":"ALL","scopeValue":"",
                                 "strategyKey":" ","totalStock":120,"perUserLimit":2,
                                 "validStartAt":"2026-07-08T00:00:00","validEndAt":"2026-08-08T23:59:59",
                                 "status":"ENABLED","sortOrder":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Integer updatedStock = jdbcClient.sql("""
                        select total_stock
                        from coupon_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query(Integer.class)
                .single();
        String updatedStrategyKey = jdbcClient.sql("""
                        select strategy_key
                        from coupon_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query(String.class)
                .single();
        assertThat(updatedStock).isEqualTo(120);
        assertThat(updatedStrategyKey).isEqualTo("coupon.amount-off.v1");

        mockMvc.perform(post("/admin/marketing/coupons/templates/{templateId}/disable", templateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/marketing/coupons/templates/{templateId}/enable", templateId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String statusValue = jdbcClient.sql("""
                        select status
                        from coupon_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query(String.class)
                .single();
        assertThat(statusValue).isEqualTo("ENABLED");
    }

    @Test
    void updateRejectsTotalStockLowerThanClaimedCount() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long templateId = seedTemplate("Stock validation", 10, 6, "DISABLED");

        mockMvc.perform(put("/admin/marketing/coupons/templates/" + templateId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Stock validation","description":"update","couponType":"MIN_SPEND","discountType":"AMOUNT_OFF",
                                 "thresholdCent":2000,"discountCent":500,"scopeType":"ALL","scopeValue":"",
                                 "strategyKey":"coupon.amount-off.v1","totalStock":5,"perUserLimit":1,
                                 "validStartAt":"2026-07-07T00:00:00","validEndAt":"2026-08-07T23:59:59",
                                 "status":"DISABLED","sortOrder":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void createRejectsDiscountAmountEqualToThreshold() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(post("/admin/marketing/coupons/templates")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad","couponType":"MIN_SPEND","discountType":"AMOUNT_OFF",
                                 "thresholdCent":1000,"discountCent":1000,"scopeType":"ALL","scopeValue":"",
                                 "totalStock":10,"perUserLimit":1,"validStartAt":"2026-07-07T00:00:00",
                                 "validEndAt":"2026-08-07T23:59:59","status":"DISABLED","sortOrder":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    private long seedTemplate(String name, int totalStock, int claimedCount, String status) {
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'seed', 'MIN_SPEND', 'AMOUNT_OFF', 2000, 500,
                             'ALL', '', 'coupon.amount-off.v1', :totalStock, :claimedCount, 1,
                             timestamp '2026-07-07 00:00:00', timestamp '2026-08-07 23:59:59', :status, 1)
                        """)
                .param("name", name)
                .param("totalStock", totalStock)
                .param("claimedCount", claimedCount)
                .param("status", status)
                .update();
        return jdbcClient.sql("""
                        select id
                        from coupon_template
                        where name = :name
                        order by id desc
                        limit 1
                        """)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private String validTemplateJson() {
        return """
                {"name":"新人满减券","description":"new user coupon","couponType":"MIN_SPEND","discountType":"AMOUNT_OFF",
                 "thresholdCent":2000,"discountCent":500,"scopeType":"ALL","scopeValue":"",
                 "strategyKey":"","totalStock":100,"perUserLimit":1,
                 "validStartAt":"2026-07-07T00:00:00","validEndAt":"2026-08-07T23:59:59",
                 "status":"DISABLED","sortOrder":1}
                """;
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

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
