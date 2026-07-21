package org.muybaby.shopserver.product.engagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminProductReviewControllerTest {

    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong(-94900L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void clearReviews() {
        jdbcClient.sql("DELETE FROM product_review").update();
    }

    @Test
    void readPermissionSupportsFiltersAndExposesOrderContext() throws Exception {
        ReviewFixture fixture = insertReview(5, true, "筛选命中的评价");
        insertReview(3, false, "其他评价");
        String token = issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("product:review:read")
        );

        mockMvc.perform(get("/admin/product/reviews")
                        .header("Authorization", bearer(token))
                        .param("productTitle", fixture.productTitle())
                        .param("rating", "5")
                        .param("status", "PUBLISHED")
                        .param("anonymous", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(fixture.reviewId()))
                .andExpect(jsonPath("$.data.records[0].spuId").value(fixture.spuId()))
                .andExpect(jsonPath("$.data.records[0].productTitle").value(fixture.productTitle()))
                .andExpect(jsonPath("$.data.records[0].reviewerName").value(fixture.nickname()))
                .andExpect(jsonPath("$.data.records[0].anonymous").value(true))
                .andExpect(jsonPath("$.data.records[0].orderNo").value(fixture.orderNo()))
                .andExpect(jsonPath("$.data.records[0].rating").value(5))
                .andExpect(jsonPath("$.data.records[0].content").value("筛选命中的评价"));

        mockMvc.perform(get("/admin/product/reviews")
                        .header("Authorization", bearer(token))
                        .param("rating", "6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    @Test
    void moderationPermissionCanHideAndRestoreReviewWithAuditMetadata() throws Exception {
        ReviewFixture fixture = insertReview(4, false, "需要审核的评价");
        String readToken = issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("product:review:read")
        );
        String moderateToken = issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("product:review:moderate")
        );

        mockMvc.perform(put("/admin/product/reviews/" + fixture.reviewId() + "/status")
                        .header("Authorization", bearer(readToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        mockMvc.perform(put("/admin/product/reviews/" + fixture.reviewId() + "/status")
                        .header("Authorization", bearer(moderateToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HIDDEN\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/app/product/spus/" + fixture.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(0))
                .andExpect(jsonPath("$.data.page.total").value(0));

        mockMvc.perform(get("/admin/product/reviews")
                        .header("Authorization", bearer(moderateToken))
                        .param("status", "HIDDEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("HIDDEN"))
                .andExpect(jsonPath("$.data.records[0].moderatedByAdminUserId").isNumber())
                .andExpect(jsonPath("$.data.records[0].moderatedAt").isNotEmpty());

        mockMvc.perform(put("/admin/product/reviews/" + fixture.reviewId() + "/status")
                        .header("Authorization", bearer(moderateToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/app/product/spus/" + fixture.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(1));
    }

    @Test
    void adminReviewEndpointsRequireAuthenticationAndValidStatus() throws Exception {
        ReviewFixture fixture = insertReview(5, false, "鉴权评价");
        String token = issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("product:review:moderate")
        );

        mockMvc.perform(get("/admin/product/reviews"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/product/reviews/" + fixture.reviewId() + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    private ReviewFixture insertReview(int rating, boolean anonymous, String content) {
        long base = FIXTURE_SEQUENCE.decrementAndGet() * 10;
        long userId = base - 1;
        long categoryId = base - 2;
        long spuId = base - 3;
        long skuId = base - 4;
        long orderId = base - 5;
        long orderItemId = base - 6;
        long reviewId = base - 7;
        String suffix = Long.toString(Math.abs(base));
        String nickname = "评论用户" + suffix;
        String productTitle = "后台评论商品" + suffix;
        String orderNo = "ADMIN-REVIEW-" + suffix;

        jdbcClient.sql("""
                        INSERT INTO app_user (id, openid, nickname, phone_authorized, status)
                        VALUES (:id, :openid, :nickname, FALSE, 'ENABLED')
                        """)
                .param("id", userId)
                .param("openid", "admin-review-user-" + suffix)
                .param("nickname", nickname)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_category (id, parent_id, name, icon, sort_order, status)
                        VALUES (:id, 0, :name, '', 1, 'ENABLED')
                        """)
                .param("id", categoryId)
                .param("name", "后台评论分类" + suffix)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_spu (
                            id, category_id, title, main_image, selling_points, detail_html, status
                        ) VALUES (:id, :categoryId, :title, 'https://example.test/review.png', '', '', 'ON_SALE')
                        """)
                .param("id", spuId)
                .param("categoryId", categoryId)
                .param("title", productTitle)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_sku (
                            id, spu_id, sku_code, spec_json, spec_text, price_cent,
                            stock_available, image, status
                        ) VALUES (:id, :spuId, :skuCode, '{}', '默认规格', 100,
                                  1, '', 'ENABLED')
                        """)
                .param("id", skuId)
                .param("spuId", spuId)
                .param("skuCode", "ADMIN-REVIEW-SKU-" + suffix)
                .update();
        jdbcClient.sql("""
                        INSERT INTO shop_order (
                            id, order_no, user_id, status, idempotency_key, completed_at
                        ) VALUES (:id, :orderNo, :userId, 'COMPLETED', :idempotencyKey,
                                  CURRENT_TIMESTAMP)
                        """)
                .param("id", orderId)
                .param("orderNo", orderNo)
                .param("userId", userId)
                .param("idempotencyKey", "admin-review-key-" + suffix)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_item (
                            id, order_id, sku_id, spu_id, product_title, sku_code, spec_text, quantity
                        ) VALUES (:id, :orderId, :skuId, :spuId, :productTitle,
                                  :skuCode, '默认规格', 1)
                        """)
                .param("id", orderItemId)
                .param("orderId", orderId)
                .param("skuId", skuId)
                .param("spuId", spuId)
                .param("productTitle", productTitle)
                .param("skuCode", "ADMIN-REVIEW-SKU-" + suffix)
                .update();
        jdbcClient.sql("""
                        INSERT INTO product_review (
                            id, user_id, spu_id, order_item_id, rating, content, anonymous, status
                        ) VALUES (:id, :userId, :spuId, :orderItemId, :rating, :content,
                                  :anonymous, 'PUBLISHED')
                        """)
                .param("id", reviewId)
                .param("userId", userId)
                .param("spuId", spuId)
                .param("orderItemId", orderItemId)
                .param("rating", rating)
                .param("content", content)
                .param("anonymous", anonymous)
                .update();

        return new ReviewFixture(reviewId, spuId, productTitle, nickname, orderNo);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record ReviewFixture(
            long reviewId,
            long spuId,
            String productTitle,
            String nickname,
            String orderNo
    ) {
    }
}
