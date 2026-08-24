package org.muybaby.shopserver.product.engagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Autowired
    private StorageUsageService storageUsageService;

    @BeforeEach
    void clearReviews() {
        jdbcClient.sql("DELETE FROM product_review").update();
    }

    @Test
    void readPermissionSupportsFiltersAndExposesOrderContext() throws Exception {
        ReviewFixture fixture = insertReview(5, true, "筛选命中的评价");
        long imageId = insertReviewImage(fixture);
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
                .andExpect(jsonPath("$.data.records[0].orderDataCleaned").value(false))
                .andExpect(jsonPath("$.data.records[0].verifiedPurchase").value(true))
                .andExpect(jsonPath("$.data.records[0].rating").value(5))
                .andExpect(jsonPath("$.data.records[0].content").value("筛选命中的评价"))
                .andExpect(jsonPath("$.data.records[0].images.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].images[0].fileId").value(imageId));

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
    void moderationPermissionCanEditAndDeleteReviewWhileReadPermissionCannot() throws Exception {
        ReviewFixture fixture = insertReview(5, false, "管理员修改前");
        long imageId = insertReviewImage(fixture);
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

        mockMvc.perform(put("/admin/product/reviews/" + fixture.reviewId())
                        .header("Authorization", bearer(readToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":2,\"content\":\"越权修改\",\"anonymous\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/product/reviews/" + fixture.reviewId())
                        .header("Authorization", bearer(readToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/admin/product/reviews/" + fixture.reviewId())
                        .header("Authorization", bearer(moderateToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":2,\"content\":\" 管理员修改后 \",\"anonymous\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/product/reviews")
                        .header("Authorization", bearer(moderateToken))
                        .param("spuId", Long.toString(fixture.spuId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].rating").value(2))
                .andExpect(jsonPath("$.data.records[0].content").value("管理员修改后"))
                .andExpect(jsonPath("$.data.records[0].anonymous").value(true))
                .andExpect(jsonPath("$.data.records[0].moderatedByAdminUserId").isNumber())
                .andExpect(jsonPath("$.data.records[0].moderatedAt").isNotEmpty());

        mockMvc.perform(delete("/admin/product/reviews/" + fixture.reviewId())
                        .header("Authorization", bearer(moderateToken)))
                .andExpect(status().isOk());
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM product_review WHERE id = :reviewId")
                .param("reviewId", fixture.reviewId())
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM storage_asset_usage
                        WHERE owner_type = 'PRODUCT_REVIEW'
                          AND owner_id = :reviewId
                          AND status = 'ACTIVE'
                        """)
                .param("reviewId", fixture.reviewId())
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("SELECT status FROM storage_asset WHERE id = :fileId")
                .param("fileId", imageId)
                .query(String.class)
                .single()).isEqualTo("DELETE_PENDING");
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

    @Test
    void adminReviewKeepsSnapshotAndMarksCleanedOrderContext() throws Exception {
        ReviewFixture fixture = insertReview(5, false, "订单清理后仍保留的评价");
        String token = issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("product:review:read")
        );
        jdbcClient.sql("UPDATE product_review SET order_item_id = NULL WHERE id = :reviewId")
                .param("reviewId", fixture.reviewId())
                .update();
        jdbcClient.sql("DELETE FROM order_item WHERE id = :orderItemId")
                .param("orderItemId", fixture.orderItemId())
                .update();
        jdbcClient.sql("DELETE FROM shop_order WHERE id = :orderId")
                .param("orderId", fixture.orderId())
                .update();

        mockMvc.perform(get("/admin/product/reviews")
                        .header("Authorization", bearer(token))
                        .param("productTitle", fixture.productTitle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(fixture.reviewId()))
                .andExpect(jsonPath("$.data.records[0].productTitle").value(fixture.productTitle()))
                .andExpect(jsonPath("$.data.records[0].specText").value("默认规格"))
                .andExpect(jsonPath("$.data.records[0].verifiedPurchase").value(true))
                .andExpect(jsonPath("$.data.records[0].orderDataCleaned").value(true))
                .andExpect(jsonPath("$.data.records[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].orderNo").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].orderItemId").doesNotExist());
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
                            stock_available, image, status, combination_key
                        ) VALUES (:id, :spuId, :skuCode, '{}', '默认规格', 100,
                                  1, '', 'ENABLED', :skuCode)
                        """)
                .param("id", skuId)
                .param("spuId", spuId)
                .param("skuCode", "ADMIN-REVIEW-SKU-" + suffix)
                .update();
        jdbcClient.sql("""
                        INSERT INTO shop_order (
                            id, order_no, user_id, status, idempotency_key,
                            checkout_request_digest, completed_at
                        ) VALUES (:id, :orderNo, :userId, 'COMPLETED', :idempotencyKey,
                                  'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
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
                            id, user_id, spu_id, source_order_item_id, order_item_id,
                            product_title_snapshot, spec_text_snapshot, verified_purchase,
                            rating, content, anonymous, status
                        ) VALUES (:id, :userId, :spuId, :orderItemId, :orderItemId,
                                  :productTitle, '默认规格', TRUE, :rating, :content,
                                  :anonymous, 'PUBLISHED')
                        """)
                .param("id", reviewId)
                .param("userId", userId)
                .param("spuId", spuId)
                .param("orderItemId", orderItemId)
                .param("productTitle", productTitle)
                .param("rating", rating)
                .param("content", content)
                .param("anonymous", anonymous)
                .update();

        return new ReviewFixture(
                reviewId, userId, spuId, productTitle, nickname, orderId, orderNo, orderItemId
        );
    }

    private long insertReviewImage(ReviewFixture fixture) {
        String objectKey = "public/library/image/admin-review/"
                + Math.abs(fixture.reviewId()) + ".webp";
        String publicUrl = "https://cdn.example.test/" + objectKey;
        jdbcClient.sql("""
                        INSERT INTO storage_asset (
                            scope, media_kind, visibility, provider, storage_container,
                            storage_region, object_key, original_filename, content_type,
                            extension, size_bytes, sha256, public_url, status,
                            uploaded_by_type, uploaded_by_id, upload_context_type,
                            upload_context_id
                        ) VALUES (
                            'LIBRARY', 'IMAGE', 'PUBLIC', 'TENCENT_COS', 'review-test',
                            'ap-test', :objectKey, 'review.webp', 'image/webp',
                            'webp', 100, '', :publicUrl, 'ACTIVE', 'APP', :userId,
                            'PRODUCT_REVIEW_ORDER_ITEM', :orderItemId
                        )
                        """)
                .param("objectKey", objectKey)
                .param("publicUrl", publicUrl)
                .param("userId", fixture.userId())
                .param("orderItemId", fixture.orderItemId())
                .update();
        Long assetId = jdbcClient.sql("SELECT id FROM storage_asset WHERE object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO product_review_image
                            (review_id, asset_id, image_url, sort_order)
                        VALUES (:reviewId, :assetId, :imageUrl, 1)
                        """)
                .param("reviewId", fixture.reviewId())
                .param("assetId", assetId)
                .param("imageUrl", publicUrl)
                .update();
        storageUsageService.addProtectedUsage(
                assetId,
                StorageFileUsageType.PRODUCT_REVIEW_IMAGE,
                StorageUsageOwnerType.PRODUCT_REVIEW,
                fixture.reviewId(),
                "后台评论测试",
                publicUrl,
                1
        );
        return assetId;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record ReviewFixture(
            long reviewId,
            long userId,
            long spuId,
            String productTitle,
            String nickname,
            long orderId,
            String orderNo,
            long orderItemId
    ) {
    }
}
