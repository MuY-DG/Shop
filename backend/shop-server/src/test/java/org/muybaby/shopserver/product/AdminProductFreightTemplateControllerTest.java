package org.muybaby.shopserver.product;

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
class AdminProductFreightTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void defaultTemplateIsReadableAndFixedTemplateCanBeCreatedAndUpdated() throws Exception {
        String token = adminLoginAndExtractToken();

        mockMvc.perform(get("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("全国包邮"))
                .andExpect(jsonPath("$.data[0].chargeMode").value("FREE"))
                .andExpect(jsonPath("$.data[0].fixedAmountCent").value(0))
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"));

        String createResponse = mockMvc.perform(post("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(" 固定运费 ", "FIXED", 1_200L, "ENABLED", 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long templateId = objectMapper.readTree(createResponse).path("data").asLong();

        assertThat(jdbcClient.sql("""
                        select concat(name, '|', charge_mode, '|', fixed_amount_cent, '|', status, '|', sort_order)
                        from freight_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query(String.class)
                .single()).isEqualTo("固定运费|FIXED|1200|ENABLED|10");

        mockMvc.perform(put("/admin/product/freight-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("活动包邮", "FREE", null, "DISABLED", 2)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].id").value(templateId))
                .andExpect(jsonPath("$.data[1].name").value("活动包邮"))
                .andExpect(jsonPath("$.data[1].chargeMode").value("FREE"))
                .andExpect(jsonPath("$.data[1].fixedAmountCent").value(0))
                .andExpect(jsonPath("$.data[1].status").value("DISABLED"));
    }

    @Test
    void freeAndFixedModesRejectInconsistentAmounts() throws Exception {
        String token = adminLoginAndExtractToken();

        assertValidation(token, request("错误包邮", "FREE", 1L, "ENABLED", 0));
        assertValidation(token, request("零元固定", "FIXED", 0L, "ENABLED", 0));
        assertValidation(token, request("负数固定", "FIXED", -1L, "ENABLED", 0));
        assertValidation(token, request("缺少固定金额", "FIXED", null, "ENABLED", 0));
        assertValidation(token, request("缺少状态", "FREE", 0L, null, 0));
    }

    @Test
    void disablingTemplateReferencedByOnSaleProductIsRejectedUntilProductIsOffSale() throws Exception {
        String token = adminLoginAndExtractToken();
        long templateId = createTemplate(token, request("在售商品模板", "FIXED", 600L, "ENABLED", 999));
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, '运费并发分类', '', 0, 'ENABLED')
                        """).update();
        long categoryId = jdbcClient.sql("select max(id) from product_category")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, spec_type, freight_template_id,
                             selling_points, detail_html, sort_order, status)
                        values
                            (:categoryId, '在售运费商品', '', '/freight.png', 'SINGLE', :templateId,
                             '', '', 0, 'ON_SALE')
                        """)
                .param("categoryId", categoryId)
                .param("templateId", templateId)
                .update();
        long spuId = jdbcClient.sql("select max(id) from product_spu")
                .query(Long.class)
                .single();

        mockMvc.perform(put("/admin/product/freight-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("在售商品模板", "FIXED", 600L, "DISABLED", 999)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_UNAVAILABLE.code()));
        assertThat(jdbcClient.sql("select status from freight_template where id = :templateId")
                .param("templateId", templateId)
                .query(String.class)
                .single()).isEqualTo("ENABLED");

        jdbcClient.sql("update product_spu set status = 'OFF_SALE' where id = :spuId")
                .param("spuId", spuId)
                .update();
        mockMvc.perform(put("/admin/product/freight-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("在售商品模板", "FIXED", 600L, "DISABLED", 999)))
                .andExpect(status().isOk());
    }

    @Test
    void freightReadCreateAndUpdateEnforceMethodAuthorities() throws Exception {
        String unrelatedToken = limitedAdminToken(List.of("product:sku:stock"));
        String spuUpdateToken = limitedAdminToken(List.of("product:spu:update"));
        String createToken = limitedAdminToken(List.of("product:freight:create"));
        String updateToken = limitedAdminToken(List.of("product:freight:update"));
        String body = request("权限固定运费", "FIXED", 800L, "ENABLED", 0);

        mockMvc.perform(get("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        mockMvc.perform(get("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + spuUpdateToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        long templateId = createTemplate(createToken, body);

        mockMvc.perform(put("/admin/product/freight-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/product/freight-templates/{templateId}", templateId)
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("权限固定运费更新", "FIXED", 900L, "ENABLED", 0)))
                .andExpect(status().isOk());
    }

    private void assertValidation(String token, String body) throws Exception {
        mockMvc.perform(post("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    private long createTemplate(String token, String body) throws Exception {
        String response = mockMvc.perform(post("/admin/product/freight-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String request(
            String name,
            String chargeMode,
            Long fixedAmountCent,
            String templateStatus,
            Integer sortOrder
    ) {
        String amount = fixedAmountCent == null ? "null" : fixedAmountCent.toString();
        String statusJson = templateStatus == null ? "null" : "\"" + templateStatus + "\"";
        String sort = sortOrder == null ? "null" : sortOrder.toString();
        return """
                {"name":"%s","chargeMode":"%s","fixedAmountCent":%s,"status":%s,"sortOrder":%s}
                """.formatted(name, chargeMode, amount, statusJson, sort);
    }

    private String adminLoginAndExtractToken() throws Exception {
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

    private String limitedAdminToken(List<String> permissions) {
        return issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }
}
