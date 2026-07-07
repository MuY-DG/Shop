package org.muybaby.shopserver.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppCartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearCartItems() {
        jdbcClient.sql("delete from cart_item").update();
    }

    @Test
    void cartApisRequireAppToken() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(get("/app/cart/items"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addListUpdateDeleteAndClearCartItemsForCurrentUser() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-API-SKU-1", 3990L, 4990L, 10, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(skuId))
                .andExpect(jsonPath("$.data.quantity").value(2))
                .andExpect(jsonPath("$.data.priceCent").value(3990))
                .andExpect(jsonPath("$.data.lineAmountCent").value(7980))
                .andExpect(jsonPath("$.data.available").value(true));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":3}
                                """.formatted(skuId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.lineAmountCent").value(19950));

        String listResponse = mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalQuantity").value(5))
                .andExpect(jsonPath("$.data.totalAmountCent").value(19950))
                .andExpect(jsonPath("$.data.unavailableCount").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long cartItemId = objectMapper.readTree(listResponse).path("data").path("items").get(0).path("id").asLong();

        mockMvc.perform(put("/app/cart/items/{cartItemId}/quantity", cartItemId)
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(4))
                .andExpect(jsonPath("$.data.lineAmountCent").value(15960));

        mockMvc.perform(delete("/app/cart/items/{cartItemId}", cartItemId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void addAndUpdateValidateProductStatusAndStock() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-STOCK-SKU-1", 2990L, 3990L, 2, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":3}
                                """.formatted(skuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200100));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        String listResponse = mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long cartItemId = objectMapper.readTree(listResponse).path("data").path("items").get(0).path("id").asLong();

        jdbcClient.sql("""
                        update product_sku
                        set stock_available = 1, updated_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", skuId)
                .update();

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].unavailableReason").value("STOCK_SHORTAGE"))
                .andExpect(jsonPath("$.data.unavailableCount").value(1));

        mockMvc.perform(put("/app/cart/items/{cartItemId}/quantity", cartItemId)
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200100));
    }

    @Test
    void addRejectsMergedQuantityAboveMaxLimit() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-MAX-SKU-1", 2990L, 3990L, 2000, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":998}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void unavailableSkuAndUnpublishedSpuReturnBusinessErrors() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long disabledSkuId = createPublishedSku("CART-DISABLED-SKU", 2990L, 3990L, 5, "ENABLED");
        long enabledSkuId = createPublishedSku("CART-OFFSALE-SKU", 3990L, 4990L, 5, "ENABLED");
        long disabledCategorySkuId = createPublishedSku("CART-DISABLED-CATEGORY-SKU", 3590L, 4590L, 5, "ENABLED");
        long spuId = jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", enabledSkuId)
                .query(Long.class)
                .single();
        long categoryId = jdbcClient.sql("""
                        select s.category_id
                        from product_spu s
                        join product_sku k on k.spu_id = s.id
                        where k.id = :skuId
                        """)
                .param("skuId", disabledCategorySkuId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        update product_sku
                        set status = 'DISABLED', updated_at = current_timestamp
                        where id = :skuId
                        """)
                .param("skuId", disabledSkuId)
                .update();

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(disabledSkuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200002));

        adminProductService.unpublishSpu(spuId);

        jdbcClient.sql("""
                        update product_category
                        set status = 'DISABLED', updated_at = current_timestamp
                        where id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .update();

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(enabledSkuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(disabledCategorySkuId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }

    @Test
    void listKeepsCartRowWhenCategoryBecomesDisabled() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-DISABLED-CATEGORY-LIST", 4590L, 5590L, 6, "ENABLED");
        long categoryId = jdbcClient.sql("""
                        select s.category_id
                        from product_spu s
                        join product_sku k on k.spu_id = s.id
                        where k.id = :skuId
                        """)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        jdbcClient.sql("""
                        update product_category
                        set status = 'DISABLED', updated_at = current_timestamp
                        where id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .update();

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].unavailableReason").value("PRODUCT_UNAVAILABLE"));
    }

    @Test
    void cartRowsAreIsolatedByAppUser() throws Exception {
        String firstUserToken = appLoginAndExtractToken("test-login-code");
        String secondUserToken = appLoginAndExtractToken("second-login-code");
        long skuId = createPublishedSku("CART-ISOLATED-SKU", 5990L, 6990L, 10, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + firstUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":1}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + secondUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void listKeepsCartRowWhenReferencedSkuRowIsMissing() throws Exception {
        String appToken = appLoginAndExtractToken("test-login-code");
        long skuId = createPublishedSku("CART-MISSING-SKU", 4590L, 5590L, 6, "ENABLED");

        mockMvc.perform(post("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"skuId":%d,"quantity":2}
                                """.formatted(skuId)))
                .andExpect(status().isOk());

        jdbcClient.sql("""
                        delete from product_sku
                        where id = :skuId
                        """)
                .param("skuId", skuId)
                .update();

        String response = mockMvc.perform(get("/app/cart/items")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].available").value(false))
                .andExpect(jsonPath("$.data.items[0].unavailableReason").value("SKU_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].priceCent").value(0))
                .andExpect(jsonPath("$.data.items[0].lineAmountCent").value(0))
                .andExpect(jsonPath("$.data.items[0].stockAvailable").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        CartItemResponse item = objectMapper.treeToValue(
                objectMapper.readTree(response).path("data").path("items").get(0),
                CartItemResponse.class
        );
        assertThat(item.skuStatus()).isNull();
        assertThat(item.spuStatus()).isNull();
    }

    private String appLoginAndExtractToken(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
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

    private long createPublishedSku(String skuCode, long priceCent, long originalPriceCent, int stock, String skuStatus) {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Cart Category " + skuCode, "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Cart SPU " + skuCode,
                "Cart subtitle",
                "https://example.test/cart-main.jpg",
                "牛油浓香,手工炒制",
                "<p>Cart detail</p>",
                1,
                List.of("https://example.test/cart-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, skuCode, "{\"规格\":\"300g\"}", "300g", priceCent, originalPriceCent, stock, 300, "https://example.test/cart-sku.jpg", skuStatus, 1))
        ));
        adminProductService.publishSpu(spuId);
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
    }
}
