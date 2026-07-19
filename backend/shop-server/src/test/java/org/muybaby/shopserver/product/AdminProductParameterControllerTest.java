package org.muybaby.shopserver.product;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminProductParameterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void categorySpecificDisplayParameterCanBeConfiguredAndSavedOutsideSku() throws Exception {
        String token = adminLoginAndExtractToken();
        Long hotpotCategoryId = insertCategory("参数测试-火锅底料");
        Long beefBaseCategoryId = insertCategory("参数测试-牛油底料", hotpotCategoryId);
        Long cookwareCategoryId = insertCategory("参数测试-锅具");
        Long spuId = insertSpu(beefBaseCategoryId, "参数测试-牛油火锅底料");

        String createResponse = mockMvc.perform(post("/admin/product/parameter-definitions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "parameterCode":"spiciness_test",
                                  "parameterName":"辣度",
                                  "valueType":"SINGLE_SELECT",
                                  "unit":"",
                                  "description":"仅用于商品界面展示，不参与 SKU 组合",
                                  "required":true,
                                  "filterable":false,
                                  "cardVisible":true,
                                  "detailVisible":true,
                                  "sortOrder":0,
                                  "status":"ENABLED",
                                  "categoryIds":[%d],
                                  "options":[
                                    {"optionCode":"MILD","optionLabel":"微辣","displayLevel":1,"sortOrder":0},
                                    {"optionCode":"MEDIUM","optionLabel":"中辣","displayLevel":2,"sortOrder":1},
                                    {"optionCode":"HOT","optionLabel":"特辣","displayLevel":3,"sortOrder":2}
                                  ]
                                }
                                """.formatted(hotpotCategoryId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long parameterId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(get("/admin/product/parameter-definitions")
                        .param("categoryId", beefBaseCategoryId.toString())
                        .param("enabledOnly", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(parameterId))
                .andExpect(jsonPath("$.data[0].parameterCode").value("SPICINESS_TEST"))
                .andExpect(jsonPath("$.data[0].categoryIds[0]").value(hotpotCategoryId))
                .andExpect(jsonPath("$.data[0].options[1].optionLabel").value("中辣"))
                .andExpect(jsonPath("$.data[0].options[1].displayLevel").value(2));

        mockMvc.perform(get("/admin/product/parameter-definitions")
                        .param("categoryId", cookwareCategoryId.toString())
                        .param("enabledOnly", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(put("/admin/product/spus/{spuId}/parameters", spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"values":[]}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/admin/product/spus/{spuId}/parameters", spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "values":[{
                                    "parameterId":%d,
                                    "textValue":null,
                                    "numberValue":null,
                                    "booleanValue":null,
                                    "optionCodes":["MEDIUM"]
                                  }]
                                }
                                """.formatted(parameterId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/{spuId}/parameters", spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].parameterId").value(parameterId))
                .andExpect(jsonPath("$.data[0].optionCodes[0]").value("MEDIUM"))
                .andExpect(jsonPath("$.data[0].displayText").value("中辣"));

        Integer skuParameterCount = jdbcClient.sql("""
                        select count(*) from product_sku
                        where spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(skuParameterCount).isZero();

        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order, is_default,
                             combination_key)
                        values
                            (:spuId, :skuCode, '{}', '默认规格', 3990, 3990,
                             20, 0, '', 'ENABLED', 0, true, 'SINGLE')
                        """)
                .param("spuId", spuId)
                .param("skuCode", "PARAMETER-TEST-" + spuId)
                .update();
        jdbcClient.sql("update product_spu set status = 'ON_SALE' where id = :spuId")
                .param("spuId", spuId)
                .update();

        mockMvc.perform(get("/app/product/spus/{spuId}", spuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parameters[0].parameterName").value("辣度"))
                .andExpect(jsonPath("$.data.parameters[0].displayText").value("中辣"))
                .andExpect(jsonPath("$.data.parameters[0].selectedOptions[0].optionCode")
                        .value("MEDIUM"))
                .andExpect(jsonPath("$.data.parameters[0].selectedOptions[0].displayLevel").value(2));

        mockMvc.perform(get("/app/product/spus")
                        .param("categoryId", beefBaseCategoryId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].parameters[0].displayText").value("中辣"));
    }

    private Long insertCategory(String name) {
        return insertCategory(name, 0L);
    }

    private Long insertCategory(String name, Long parentId) {
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (:parentId, :name, '', 0, 'ENABLED')
                        """)
                .param("parentId", parentId)
                .param("name", name)
                .update();
        return jdbcClient.sql("select id from product_category where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private Long insertSpu(Long categoryId, String title) {
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html,
                             sort_order, status, spec_type, freight_template_id, virtual_sales)
                        values
                            (:categoryId, :title, '', 'http://localhost/parameter-test.png', '', '',
                             0, 'DRAFT', 'SINGLE', 1, 0)
                        """)
                .param("categoryId", categoryId)
                .param("title", title)
                .update();
        return jdbcClient.sql("select id from product_spu where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();
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
}
