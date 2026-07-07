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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
class AdminProductSpuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminCanCreatePublishListDetailUnpublishAndAdjustStock() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller SPU",
                                  "subtitle": "Controller subtitle",
                                  "mainImage": "https://example.test/main.jpg",
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>detail</p>",
                                  "sortOrder": 1,
                                  "images": ["https://example.test/gallery.jpg"],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-SKU-1",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 5,
                                      "weightGram": 300,
                                      "image": "https://example.test/sku.jpg",
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(post("/admin/product/spus/" + spuId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .param("current", "1")
                        .param("size", "20")
                        .param("title", "Controller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[0].status").value("ON_SALE"));

        String detailResponse = mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(5))
                .andExpect(jsonPath("$.data.skus[0].sortOrder").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long skuId = objectMapper.readTree(detailResponse).path("data").path("skus").get(0).path("id").asLong();

        mockMvc.perform(post("/admin/product/skus/" + skuId + "/stock-adjustments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityDelta": 3, "reason": "controller adjustment"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(8));

        mockMvc.perform(put("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller SPU",
                                  "subtitle": "Controller subtitle",
                                  "mainImage": "https://example.test/main.jpg",
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>detail</p>",
                                  "sortOrder": 1,
                                  "images": ["https://example.test/gallery.jpg"],
                                  "skus": [
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-SKU-1",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 10,
                                      "weightGram": 300,
                                      "image": "https://example.test/sku.jpg",
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(categoryId, skuId)))
                .andExpect(status().isOk());

        Integer updateStockAuditLogs = jdbcClient.sql("""
                        select count(*)
                        from stock_log
                        where sku_id = :skuId
                          and change_type = 'ADJUST'
                          and quantity_before = 8
                          and quantity_delta = 2
                          and quantity_after = 10
                          and operator_type = 'ADMIN'
                          and operator_id = 1
                        """)
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        assertThat(updateStockAuditLogs).isEqualTo(1);

        mockMvc.perform(post("/admin/product/spus/" + spuId + "/unpublish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"));
    }

    private long createCategory(String token) throws Exception {
        String response = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"SPU Controller Category","icon":"","sortOrder":1,"status":"ENABLED"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String loginAndExtractToken() throws Exception {
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
}
