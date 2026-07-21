package org.muybaby.shopserver.product.engagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminProductImageUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppProductEngagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearEngagementTables() {
        jdbcClient.sql("DELETE FROM product_review").update();
        jdbcClient.sql("DELETE FROM user_product_favorite").update();
        jdbcClient.sql("DELETE FROM user_product_browse_history").update();
    }

    @Test
    void favoritesAndBrowseHistoryAreAccountScopedIdempotentAndManageable() throws Exception {
        AppLogin first = login("engagement-preference-first");
        AppLogin second = login("engagement-preference-second");
        ProductIds product = createPublishedProduct("PREFERENCE");

        mockMvc.perform(put("/app/users/me/favorites/" + product.spuId()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/app/users/me/favorites/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spuId").value(product.spuId()))
                .andExpect(jsonPath("$.data.favorited").value(true));
        mockMvc.perform(put("/app/users/me/favorites/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/users/me/favorites/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(true));
        mockMvc.perform(get("/app/users/me/favorites")
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].spuId").value(product.spuId()))
                .andExpect(jsonPath("$.data.records[0].available").value(true));
        mockMvc.perform(get("/app/users/me/favorites/" + product.spuId())
                        .header("Authorization", bearer(second.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorited").value(false));

        mockMvc.perform(post("/app/users/me/browse-history/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(post("/app/users/me/browse-history/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewCount").value(2));
        mockMvc.perform(get("/app/users/me/browse-history")
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].viewCount").value(2));
        mockMvc.perform(get("/app/users/me/browse-history")
                        .header("Authorization", bearer(second.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(delete("/app/users/me/favorites/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/app/users/me/favorites/" + product.spuId())
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/app/users/me/browse-history")
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/users/me/favorites")
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/app/users/me/browse-history")
                        .header("Authorization", bearer(first.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void completedPurchaseReviewSupportsEligibilityCrudPublicPageAndSummary() throws Exception {
        AppLogin owner = login("engagement-review-owner");
        AppLogin other = login("engagement-review-other");
        ProductIds product = createPublishedProduct("REVIEW");
        long orderItemId = insertCompletedOrder(owner.userId(), product);

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/review-eligibility")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems.length()").value(1))
                .andExpect(jsonPath("$.data.orderItems[0].orderItemId").value(orderItemId));

        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 5, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200201));

        MvcResult created = mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 5, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.anonymous").value(true))
                .andExpect(jsonPath("$.data.reviewerName").value("匿名用户"))
                .andExpect(jsonPath("$.data.verifiedPurchase").value(true))
                .andReturn();
        long reviewId = read(created).path("data").path("id").asLong();

        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 4, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200202));

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(1))
                .andExpect(jsonPath("$.data.summary.averageRating").value(5.0))
                .andExpect(jsonPath("$.data.page.records[0].reviewerName").value("匿名用户"));
        mockMvc.perform(get("/app/product/spus/" + product.spuId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSummary.reviewCount").value(1))
                .andExpect(jsonPath("$.data.reviewSummary.goodReviewCount").value(1));

        mockMvc.perform(put("/app/product/reviews/" + reviewId)
                        .header("Authorization", bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3,\"content\":\"越权修改\",\"anonymous\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200200));

        mockMvc.perform(put("/app/product/reviews/" + reviewId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"content\":\" 更新后的评价 \",\"anonymous\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(4))
                .andExpect(jsonPath("$.data.content").value("更新后的评价"))
                .andExpect(jsonPath("$.data.anonymous").value(false));
        mockMvc.perform(get("/app/product/reviews/mine")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(reviewId));

        mockMvc.perform(delete("/app/product/reviews/" + reviewId)
                        .header("Authorization", bearer(other.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200200));
        mockMvc.perform(delete("/app/product/reviews/" + reviewId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(0))
                .andExpect(jsonPath("$.data.page.total").value(0));
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/review-eligibility")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems.length()").value(1));
    }

    @Test
    void reviewsValidateAuthenticationRatingAndCompletedOrder() throws Exception {
        AppLogin owner = login("engagement-review-validation");
        ProductIds product = createPublishedProduct("VALIDATION");
        long orderItemId = insertOrder(owner.userId(), product, "PAID", null);

        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 5, false)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 6, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 5, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200201));
    }

    private AppLogin login(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = read(result).path("data");
        return new AppLogin(data.path("token").asText(), data.path("user").path("userId").asLong());
    }

    private ProductIds createPublishedProduct(String marker) {
        String suffix = marker + "-" + System.nanoTime();
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "互动分类-" + suffix, "", null, 1, "ENABLED"
        ));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "互动商品-" + suffix,
                "商品互动测试",
                "https://example.test/engagement-main.jpg",
                null,
                "真实购买,用户评价",
                "<p>商品详情</p>",
                1,
                List.of(new AdminProductImageUpsertRequest(
                        "https://example.test/engagement-gallery.jpg", null
                )),
                List.of(new AdminSkuUpsertRequest(
                        null, "ENGAGEMENT-SKU-" + suffix, "{}", "默认规格",
                        3990L, 4990L, 10, 300,
                        "https://example.test/engagement-sku.jpg", null, "ENABLED", 1
                ))
        ));
        adminProductService.publishSpu(spuId);
        Long skuId = jdbcClient.sql("SELECT id FROM product_sku WHERE spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        return new ProductIds(spuId, skuId);
    }

    private long insertCompletedOrder(long userId, ProductIds product) {
        return insertOrder(userId, product, "COMPLETED", java.time.LocalDateTime.now());
    }

    private long insertOrder(
            long userId,
            ProductIds product,
            String statusValue,
            java.time.LocalDateTime completedAt
    ) {
        String suffix = Long.toString(System.nanoTime());
        jdbcClient.sql("""
                        INSERT INTO shop_order (
                            order_no, user_id, status, idempotency_key, completed_at
                        ) VALUES (:orderNo, :userId, :status, :idempotencyKey, :completedAt)
                        """)
                .param("orderNo", "ENG" + suffix)
                .param("userId", userId)
                .param("status", statusValue)
                .param("idempotencyKey", "engagement-" + suffix)
                .param("completedAt", completedAt)
                .update();
        Long orderId = jdbcClient.sql("SELECT id FROM shop_order WHERE order_no = :orderNo")
                .param("orderNo", "ENG" + suffix)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO order_item (
                            order_id, sku_id, spu_id, product_title, sku_code, spec_text, quantity
                        ) VALUES (:orderId, :skuId, :spuId, '互动商品', :skuCode, '默认规格', 1)
                        """)
                .param("orderId", orderId)
                .param("skuId", product.skuId())
                .param("spuId", product.spuId())
                .param("skuCode", "ENG-ORDER-SKU-" + suffix)
                .update();
        return jdbcClient.sql("SELECT id FROM order_item WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private String reviewBody(long orderItemId, int rating, boolean anonymous) {
        return """
                {"orderItemId":%d,"rating":%d,"content":"味道很好","anonymous":%s}
                """.formatted(orderItemId, rating, anonymous);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record AppLogin(String token, long userId) {
    }

    private record ProductIds(long spuId, long skuId) {
    }
}
