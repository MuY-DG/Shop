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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppProductEngagementControllerTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("product_engagement")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "set time_zone = '+00:00'");
    }

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
        jdbcClient.sql("DELETE FROM storage_asset_usage WHERE owner_type = 'PRODUCT_REVIEW'").update();
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
    void completedPurchaseReviewSupportsOneTimeCreateAndImmutablePublicPage() throws Exception {
        AppLogin owner = login("engagement-review-owner");
        AppLogin other = login("engagement-review-other");
        ProductIds product = createPublishedProduct("REVIEW");
        long orderItemId = insertCompletedOrder(owner.userId(), product);
        long firstImageId = insertReviewImageAsset(owner.userId(), orderItemId, "first");
        long secondImageId = insertReviewImageAsset(owner.userId(), orderItemId, "second");

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
                        .content(reviewBody(
                                orderItemId, 5, true, List.of(secondImageId, firstImageId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.anonymous").value(true))
                .andExpect(jsonPath("$.data.reviewerName").value("匿名用户"))
                .andExpect(jsonPath("$.data.verifiedPurchase").value(true))
                .andExpect(jsonPath("$.data.images.length()").value(2))
                .andExpect(jsonPath("$.data.images[0].fileId").value(secondImageId))
                .andExpect(jsonPath("$.data.images[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.images[1].fileId").value(firstImageId))
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
                .andExpect(jsonPath("$.data.summary.goodReviewCount").value(1))
                .andExpect(jsonPath("$.data.summary.imageReviewCount").value(1))
                .andExpect(jsonPath("$.data.summary.criticalReviewCount").value(0))
                .andExpect(jsonPath("$.data.page.records[0].reviewerName").value("匿名用户"))
                .andExpect(jsonPath("$.data.page.records[0].images[0].fileId").value(secondImageId));
        mockMvc.perform(get("/app/product/spus/" + product.spuId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewSummary.reviewCount").value(1))
                .andExpect(jsonPath("$.data.reviewSummary.goodReviewCount").value(1))
                .andExpect(jsonPath("$.data.reviewSummary.imageReviewCount").value(1))
                .andExpect(jsonPath("$.data.reviewSummary.criticalReviewCount").value(0));

        mockMvc.perform(put("/app/product/reviews/" + reviewId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"content\":\"不允许修改\",\"anonymous\":false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/app/product/reviews/" + reviewId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isNotFound());

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM storage_asset_usage
                        WHERE owner_type = 'PRODUCT_REVIEW'
                          AND owner_id = :reviewId
                          AND status = 'ACTIVE'
                        """)
                .param("reviewId", reviewId)
                .query(Integer.class)
                .single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM storage_asset
                        WHERE id IN (:fileIds) AND expires_at IS NULL
                        """)
                .param("fileIds", List.of(firstImageId, secondImageId))
                .query(Integer.class)
                .single()).isEqualTo(2);

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(1))
                .andExpect(jsonPath("$.data.page.total").value(1))
                .andExpect(jsonPath("$.data.page.records[0].rating").value(5));
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/review-eligibility")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems.length()").value(0));
    }

    @Test
    void publicReviewsSupportGoodFirstSortingMediaRatingAndSpecificationFilters() throws Exception {
        AppLogin imageReviewer = login("engagement-review-filter-image");
        AppLogin latestGoodReviewer = login("engagement-review-filter-latest-good");
        AppLogin criticalReviewer = login("engagement-review-filter-critical");
        ProductIds product = createPublishedProduct("REVIEW-FILTERS");
        long imageOrderItemId = insertCompletedOrder(imageReviewer.userId(), product);
        long latestGoodOrderItemId = insertCompletedOrder(latestGoodReviewer.userId(), product);
        long criticalOrderItemId = insertCompletedOrder(criticalReviewer.userId(), product);
        long imageFileId = insertReviewImageAsset(
                imageReviewer.userId(), imageOrderItemId, "filter-image");

        long imageReviewId = read(mockMvc.perform(post(
                                "/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(imageReviewer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(imageOrderItemId, 4, false, List.of(imageFileId))))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();
        long latestGoodReviewId = read(mockMvc.perform(post(
                                "/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(latestGoodReviewer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(latestGoodOrderItemId, 5, false)))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();
        long criticalReviewId = read(mockMvc.perform(post(
                                "/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(criticalReviewer.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(criticalOrderItemId, 2, false)))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();

        updateReviewCreatedAt(imageReviewId, "2025-01-01 00:00:00");
        updateReviewCreatedAt(latestGoodReviewId, "2025-01-02 00:00:00");
        updateReviewCreatedAt(criticalReviewId, "2025-01-03 00:00:00");

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.reviewCount").value(3))
                .andExpect(jsonPath("$.data.summary.goodReviewCount").value(2))
                .andExpect(jsonPath("$.data.summary.imageReviewCount").value(1))
                .andExpect(jsonPath("$.data.summary.criticalReviewCount").value(1))
                .andExpect(jsonPath("$.data.page.records[0].id").value(imageReviewId))
                .andExpect(jsonPath("$.data.page.records[1].id").value(latestGoodReviewId))
                .andExpect(jsonPath("$.data.page.records[2].id").value(criticalReviewId));

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews")
                        .param("sort", "LATEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.records[0].id").value(latestGoodReviewId))
                .andExpect(jsonPath("$.data.page.records[1].id").value(imageReviewId))
                .andExpect(jsonPath("$.data.page.records[2].id").value(criticalReviewId));

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews")
                        .param("filter", "WITH_IMAGES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(1))
                .andExpect(jsonPath("$.data.page.records[0].id").value(imageReviewId));
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews")
                        .param("filter", "GOOD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(2));
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews")
                        .param("filter", "CRITICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(1))
                .andExpect(jsonPath("$.data.page.records[0].id").value(criticalReviewId));
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews")
                        .param("specText", "默认规格"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(3));
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews")
                        .param("specText", "不存在的规格"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(0));
    }

    @Test
    void reviewsKeepTheirSnapshotsAfterOrderDataIsCleaned() throws Exception {
        AppLogin owner = login("engagement-review-cleaned-order");
        ProductIds product = createPublishedProduct("REVIEW-CLEANED-ORDER");
        long orderItemId = insertCompletedOrder(owner.userId(), product);
        Long orderId = jdbcClient.sql("SELECT order_id FROM order_item WHERE id = :orderItemId")
                .param("orderItemId", orderItemId)
                .query(Long.class)
                .single();

        MvcResult created = mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 5, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItemId").value(orderItemId))
                .andExpect(jsonPath("$.data.productTitle").value("互动商品"))
                .andExpect(jsonPath("$.data.skuSpecText").value("默认规格"))
                .andExpect(jsonPath("$.data.verifiedPurchase").value(true))
                .andReturn();
        long reviewId = read(created).path("data").path("id").asLong();

        jdbcClient.sql("""
                        UPDATE product_review
                        SET order_item_id = NULL
                        WHERE id = :reviewId
                        """)
                .param("reviewId", reviewId)
                .update();
        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/review-eligibility")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems.length()").value(0));
        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 4, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200202));
        jdbcClient.sql("DELETE FROM order_item WHERE id = :orderItemId")
                .param("orderItemId", orderItemId)
                .update();
        jdbcClient.sql("DELETE FROM shop_order WHERE id = :orderId")
                .param("orderId", orderId)
                .update();

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.total").value(1))
                .andExpect(jsonPath("$.data.page.records[0].skuSpecText").value("默认规格"))
                .andExpect(jsonPath("$.data.page.records[0].verifiedPurchase").value(true));
        mockMvc.perform(get("/app/product/reviews/mine")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isNotFound());

        PersistedReviewSnapshot persistedSnapshot = jdbcClient.sql("""
                        SELECT source_order_item_id, product_title_snapshot,
                               spec_text_snapshot, verified_purchase
                        FROM product_review
                        WHERE id = :reviewId
                        """)
                .param("reviewId", reviewId)
                .query((rs, rowNum) -> new PersistedReviewSnapshot(
                        rs.getLong("source_order_item_id"),
                        rs.getString("product_title_snapshot"),
                        rs.getString("spec_text_snapshot"),
                        rs.getBoolean("verified_purchase")
                ))
                .single();
        assertThat(persistedSnapshot).isEqualTo(new PersistedReviewSnapshot(
                orderItemId, "互动商品", "默认规格", true
        ));
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

    @Test
    void reviewImagesRejectForeignContextAndMoreThanSixFiles() throws Exception {
        AppLogin owner = login("engagement-review-image-owner");
        AppLogin other = login("engagement-review-image-other");
        ProductIds product = createPublishedProduct("REVIEW-IMAGE-VALIDATION");
        long orderItemId = insertCompletedOrder(owner.userId(), product);
        long foreignImageId = insertReviewImageAsset(other.userId(), orderItemId, "foreign");

        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(orderItemId, 5, false, List.of(foreignImageId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(800001));

        mockMvc.perform(post("/app/product/spus/" + product.spuId() + "/reviews")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody(
                                orderItemId,
                                5,
                                false,
                                List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    @Test
    void reviewImageUploadIsAuthenticatedAndCompletedOrderScoped() throws Exception {
        AppLogin owner = login("engagement-review-upload-owner");
        AppLogin other = login("engagement-review-upload-other");
        ProductIds product = createPublishedProduct("REVIEW-UPLOAD");
        long orderItemId = insertCompletedOrder(owner.userId(), product);

        mockMvc.perform(multipart(
                                "/app/product/order-items/{orderItemId}/review-images",
                                orderItemId)
                        .file(new MockMultipartFile(
                                "file", "review.png", "image/png", TINY_PNG)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart(
                                "/app/product/order-items/{orderItemId}/review-images",
                                orderItemId)
                        .file(new MockMultipartFile(
                                "file", "review.png", "image/png", TINY_PNG))
                        .header("Authorization", bearer(other.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200201));

        MvcResult upload = mockMvc.perform(multipart(
                                "/app/product/order-items/{orderItemId}/review-images",
                                orderItemId)
                        .file(new MockMultipartFile(
                                "file", "review.png", "image/png", TINY_PNG))
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("LIBRARY"))
                .andExpect(jsonPath("$.data.mediaKind").value("IMAGE"))
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.uploadedByType").value("APP"))
                .andExpect(jsonPath("$.data.publicUrl").isNotEmpty())
                .andReturn();
        long assetId = read(upload).path("data").path("id").asLong();

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM storage_asset
                        WHERE id = :assetId
                          AND uploaded_by_id = :userId
                          AND upload_context_type = 'PRODUCT_REVIEW_ORDER_ITEM'
                          AND upload_context_id = :orderItemId
                          AND expires_at > CURRENT_TIMESTAMP
                        """)
                .param("assetId", assetId)
                .param("userId", owner.userId())
                .param("orderItemId", orderItemId)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void appDeletedCompletedOrderIsNotReviewEligible() throws Exception {
        AppLogin owner = login("engagement-review-deleted-order");
        ProductIds product = createPublishedProduct("DELETED-ORDER");
        long orderItemId = insertCompletedOrder(owner.userId(), product);
        jdbcClient.sql("""
                        UPDATE shop_order
                        SET app_deleted_at = CURRENT_TIMESTAMP
                        WHERE id = (SELECT order_id FROM order_item WHERE id = :orderItemId)
                        """)
                .param("orderItemId", orderItemId)
                .update();

        mockMvc.perform(get("/app/product/spus/" + product.spuId() + "/review-eligibility")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItems.length()").value(0));

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
        return reviewBody(orderItemId, rating, anonymous, List.of());
    }

    private String reviewBody(
            long orderItemId,
            int rating,
            boolean anonymous,
            List<Long> imageFileIds
    ) {
        return """
                {"orderItemId":%d,"rating":%d,"content":"味道很好","anonymous":%s,"imageFileIds":%s}
                """.formatted(orderItemId, rating, anonymous, imageFileIds);
    }

    private long insertReviewImageAsset(long userId, long orderItemId, String marker) {
        String objectKey = "public/library/image/review/" + marker + "-" + System.nanoTime() + ".webp";
        String publicUrl = "https://cdn.example.test/" + objectKey;
        jdbcClient.sql("""
                        INSERT INTO storage_asset (
                            scope, media_kind, visibility, provider, storage_container,
                            storage_region, object_key, original_filename, content_type,
                            extension, size_bytes, sha256, public_url, status,
                            uploaded_by_type, uploaded_by_id, upload_context_type,
                            upload_context_id, expires_at
                        ) VALUES (
                            'LIBRARY', 'IMAGE', 'PUBLIC', 'TENCENT_COS', 'review-test',
                            'ap-test', :objectKey, :filename, 'image/webp',
                            'webp', 100, '', :publicUrl, 'ACTIVE',
                            'APP', :userId, 'PRODUCT_REVIEW_ORDER_ITEM',
                            :orderItemId, TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP)
                        )
                        """)
                .param("objectKey", objectKey)
                .param("filename", marker + ".webp")
                .param("publicUrl", publicUrl)
                .param("userId", userId)
                .param("orderItemId", orderItemId)
                .update();
        return jdbcClient.sql("SELECT id FROM storage_asset WHERE object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private void updateReviewCreatedAt(long reviewId, String createdAt) {
        jdbcClient.sql("""
                        UPDATE product_review
                        SET created_at = :createdAt, updated_at = :createdAt
                        WHERE id = :reviewId
                        """)
                .param("createdAt", java.time.LocalDateTime.parse(createdAt.replace(' ', 'T')))
                .param("reviewId", reviewId)
                .update();
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

    private record PersistedReviewSnapshot(
            long sourceOrderItemId,
            String productTitle,
            String specText,
            boolean verifiedPurchase
    ) {
    }
}
