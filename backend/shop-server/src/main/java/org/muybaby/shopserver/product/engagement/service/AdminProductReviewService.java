package org.muybaby.shopserver.product.engagement.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.engagement.ProductReviewStatus;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewQueryRequest;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewUpdateRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewImageResponse;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminProductReviewService {

    private static final String REVIEW_IMAGE_CONTEXT = "PRODUCT_REVIEW_ORDER_ITEM";

    private static final String REVIEW_FROM = """
            FROM product_review r
            JOIN app_user u ON u.id = r.user_id
            LEFT JOIN product_spu p ON p.id = r.spu_id
            LEFT JOIN order_item oi ON oi.id = r.order_item_id
            LEFT JOIN shop_order o ON o.id = oi.order_id
            """;

    private final JdbcClient jdbcClient;
    private final StorageUsageService storageUsageService;

    public AdminProductReviewService(
            JdbcClient jdbcClient,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.storageUsageService = storageUsageService;
    }

    public PageResult<AdminProductReviewResponse> page(AdminProductReviewQueryRequest request) {
        AdminProductReviewQueryRequest query = request == null
                ? new AdminProductReviewQueryRequest(null, null, null, null, null, null, null)
                : request;
        Map<String, Object> parameters = new HashMap<>();
        String where = buildWhere(query, parameters);
        long current = query.pageCurrent();
        long size = query.pageSize();
        long offset = (current - 1) * size;

        Long total = jdbcClient.sql("SELECT COUNT(*) " + REVIEW_FROM + where)
                .params(parameters)
                .query(Long.class)
                .single();

        parameters.put("limit", size);
        parameters.put("offset", offset);
        List<AdminProductReviewResponse> records = jdbcClient.sql("""
                        SELECT r.id, r.spu_id,
                               r.product_title_snapshot AS product_title,
                               p.main_image AS product_image,
                               r.user_id,
                               CASE WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               o.id AS order_id, o.order_no, r.order_item_id,
                               r.spec_text_snapshot AS spec_text,
                               CASE WHEN r.order_item_id IS NULL OR o.id IS NULL
                                    THEN TRUE ELSE FALSE END AS order_data_cleaned,
                               r.verified_purchase,
                               r.rating, r.content, r.anonymous, r.status,
                               r.created_at, r.updated_at,
                               r.moderated_by_admin_user_id, r.moderated_at
                        """ + REVIEW_FROM + where + "\n" + """
                        ORDER BY r.created_at DESC, r.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .params(parameters)
                .query(this::mapReview)
                .list();

        records = attachImages(records);
        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    @Transactional
    public void updateStatus(Long reviewId, ProductReviewStatus status, long adminUserId) {
        int updated = jdbcClient.sql("""
                        UPDATE product_review
                        SET status = :status,
                            moderated_by_admin_user_id = :adminUserId,
                            moderated_at = :moderatedAt
                        WHERE id = :reviewId
                        """)
                .param("status", status.name())
                .param("adminUserId", adminUserId)
                .param("moderatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("reviewId", reviewId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND);
        }
    }

    @Transactional
    public void update(
            Long reviewId,
            AdminProductReviewUpdateRequest request,
            long adminUserId
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE product_review
                        SET rating = :rating,
                            content = :content,
                            anonymous = :anonymous,
                            updated_at = :updatedAt,
                            moderated_by_admin_user_id = :adminUserId,
                            moderated_at = :moderatedAt
                        WHERE id = :reviewId
                        """)
                .param("rating", request.rating())
                .param("content", normalizeContent(request.content()))
                .param("anonymous", Boolean.TRUE.equals(request.anonymous()))
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("adminUserId", adminUserId)
                .param("moderatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("reviewId", reviewId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND);
        }
    }

    @Transactional
    public void delete(Long reviewId) {
        Long userId = jdbcClient.sql("""
                        SELECT user_id FROM product_review
                        WHERE id = :reviewId
                        FOR UPDATE
                        """)
                .param("reviewId", reviewId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND));
        List<Long> imageFileIds = jdbcClient.sql("""
                        SELECT asset_id FROM product_review_image
                        WHERE review_id = :reviewId
                        ORDER BY sort_order, id
                        FOR UPDATE
                        """)
                .param("reviewId", reviewId)
                .query(Long.class)
                .list();
        storageUsageService.removeOwnerUsages(StorageUsageOwnerType.PRODUCT_REVIEW, reviewId);
        int deleted = jdbcClient.sql("DELETE FROM product_review WHERE id = :reviewId")
                .param("reviewId", reviewId)
                .update();
        if (deleted != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND);
        }
        scheduleReviewImagesForCleanup(userId, imageFileIds);
    }

    private void scheduleReviewImagesForCleanup(long userId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        int updated = jdbcClient.sql("""
                        UPDATE storage_asset
                        SET status = 'DELETE_PENDING',
                            folder_id = NULL,
                            public_url = NULL,
                            expires_at = NULL,
                            cleanup_attempts = 0,
                            cleanup_next_retry_at = CURRENT_TIMESTAMP,
                            cleanup_lease_token = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id IN (:fileIds)
                          AND status = 'ACTIVE'
                          AND uploaded_by_type = 'APP'
                          AND uploaded_by_id = :userId
                          AND upload_context_type = :contextType
                          AND NOT EXISTS (
                              SELECT 1 FROM storage_asset_usage usage_ref
                              WHERE usage_ref.asset_id = storage_asset.id
                                AND usage_ref.status = 'ACTIVE'
                          )
                        """)
                .param("fileIds", fileIds)
                .param("userId", userId)
                .param("contextType", REVIEW_IMAGE_CONTEXT)
                .update();
        if (updated != fileIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    private String normalizeContent(String content) {
        return content == null ? "" : content.trim();
    }

    private String buildWhere(AdminProductReviewQueryRequest query, Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.spuId() != null) {
            where.append(" AND r.spu_id = :spuId");
            parameters.put("spuId", query.spuId());
        }
        if (!query.normalizedProductTitle().isEmpty()) {
            where.append(" AND r.product_title_snapshot LIKE CONCAT('%', :productTitle, '%')");
            parameters.put("productTitle", query.normalizedProductTitle());
        }
        if (query.rating() != null) {
            where.append(" AND r.rating = :rating");
            parameters.put("rating", query.rating());
        }
        if (query.status() != null) {
            where.append(" AND r.status = :status");
            parameters.put("status", query.status().name());
        }
        if (query.anonymous() != null) {
            where.append(" AND r.anonymous = :anonymous");
            parameters.put("anonymous", query.anonymous());
        }
        return where.toString();
    }

    private AdminProductReviewResponse mapReview(ResultSet rs, int rowNum) throws SQLException {
        return new AdminProductReviewResponse(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getString("product_title"),
                rs.getString("product_image"),
                rs.getLong("user_id"),
                rs.getString("reviewer_name"),
                rs.getObject("order_id", Long.class),
                rs.getString("order_no"),
                rs.getObject("order_item_id", Long.class),
                rs.getBoolean("order_data_cleaned"),
                rs.getString("spec_text"),
                rs.getBoolean("verified_purchase"),
                rs.getInt("rating"),
                rs.getString("content"),
                rs.getBoolean("anonymous"),
                ProductReviewStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("moderated_by_admin_user_id", Long.class),
                rs.getObject("moderated_at", LocalDateTime.class),
                List.of()
        );
    }

    private List<AdminProductReviewResponse> attachImages(
            List<AdminProductReviewResponse> reviews
    ) {
        if (reviews.isEmpty()) {
            return reviews;
        }
        Map<Long, List<ProductReviewImageResponse>> images = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT review_id, asset_id, image_url, sort_order
                        FROM product_review_image
                        WHERE review_id IN (:reviewIds)
                        ORDER BY review_id, sort_order, id
                        """)
                .param("reviewIds", reviews.stream().map(AdminProductReviewResponse::id).toList())
                .query((rs, rowNum) -> new ReviewImageProjection(
                        rs.getLong("review_id"),
                        new ProductReviewImageResponse(
                                rs.getLong("asset_id"),
                                rs.getString("image_url"),
                                rs.getInt("sort_order")
                        )
                ))
                .list()
                .forEach(row -> images.computeIfAbsent(
                        row.reviewId(), ignored -> new ArrayList<>()).add(row.image()));
        return reviews.stream()
                .map(review -> withImages(
                        review, images.getOrDefault(review.id(), List.of())))
                .toList();
    }

    private AdminProductReviewResponse withImages(
            AdminProductReviewResponse review,
            List<ProductReviewImageResponse> images
    ) {
        return new AdminProductReviewResponse(
                review.id(), review.spuId(), review.productTitle(), review.productImage(),
                review.userId(), review.reviewerName(), review.orderId(), review.orderNo(),
                review.orderItemId(), review.orderDataCleaned(), review.specText(),
                review.verifiedPurchase(), review.rating(), review.content(), review.anonymous(),
                review.status(), review.createdAt(), review.updatedAt(),
                review.moderatedByAdminUserId(), review.moderatedAt(), List.copyOf(images)
        );
    }

    private record ReviewImageProjection(Long reviewId, ProductReviewImageResponse image) {
    }
}
