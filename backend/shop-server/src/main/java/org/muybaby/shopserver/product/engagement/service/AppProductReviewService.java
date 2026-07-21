package org.muybaby.shopserver.product.engagement.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.dto.AppProductReviewSummaryResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductEngagementPageRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewEligibilityResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewPageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewUpdateRequest;
import org.muybaby.shopserver.product.engagement.dto.PublicProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.ReviewableOrderItemResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AppProductReviewService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String COMPLETED = "COMPLETED";

    private final JdbcClient jdbcClient;

    public AppProductReviewService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ProductReviewPageResponse page(Long spuId, ProductEngagementPageRequest request) {
        requireVisibleProduct(spuId);
        ProductEngagementPageRequest normalized = normalized(request);
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        long total = reviewCount(spuId);
        List<PublicProductReviewResponse> records = jdbcClient.sql("""
                        SELECT r.id, oi.spec_text, r.rating, r.content, r.anonymous,
                               CASE WHEN r.anonymous = TRUE THEN '匿名用户'
                                    WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               r.created_at, r.updated_at
                        FROM product_review r
                        JOIN app_user u ON u.id = r.user_id
                        JOIN order_item oi ON oi.id = r.order_item_id
                        WHERE r.spu_id = :spuId
                          AND r.status = :status
                        ORDER BY r.created_at DESC, r.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("spuId", spuId)
                .param("status", PUBLISHED)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapPublicReview)
                .list();
        return new ProductReviewPageResponse(
                summary(spuId),
                PageResult.of(records, total, current, size)
        );
    }

    public PageResult<ProductReviewResponse> mine(
            AuthenticatedPrincipal principal,
            ProductEngagementPageRequest request
    ) {
        long userId = requireAppUser(principal);
        ProductEngagementPageRequest normalized = normalized(request);
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        Long total = jdbcClient.sql("""
                        SELECT COUNT(*) FROM product_review
                        WHERE user_id = :userId AND status = :status
                        """)
                .param("userId", userId)
                .param("status", PUBLISHED)
                .query(Long.class)
                .single();
        List<ProductReviewResponse> records = jdbcClient.sql("""
                        SELECT r.id, r.spu_id, p.title AS product_title, r.order_item_id,
                               oi.spec_text, r.rating, r.content, r.anonymous,
                               CASE WHEN r.anonymous = TRUE THEN '匿名用户'
                                    WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               r.created_at, r.updated_at
                        FROM product_review r
                        JOIN app_user u ON u.id = r.user_id
                        JOIN product_spu p ON p.id = r.spu_id
                        JOIN order_item oi ON oi.id = r.order_item_id
                        WHERE r.user_id = :userId
                          AND r.status = :status
                        ORDER BY r.created_at DESC, r.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("userId", userId)
                .param("status", PUBLISHED)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapReview)
                .list();
        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    public ProductReviewEligibilityResponse eligibility(
            AuthenticatedPrincipal principal,
            Long spuId
    ) {
        long userId = requireAppUser(principal);
        requireExistingProduct(spuId);
        List<ReviewableOrderItemResponse> orderItems = jdbcClient.sql("""
                        SELECT oi.id AS order_item_id, o.id AS order_id, o.order_no,
                               oi.sku_id, oi.spec_text, o.completed_at
                        FROM order_item oi
                        JOIN shop_order o ON o.id = oi.order_id
                        LEFT JOIN product_review r ON r.order_item_id = oi.id
                        WHERE o.user_id = :userId
                          AND oi.spu_id = :spuId
                          AND o.status = :completedStatus
                          AND o.completed_at IS NOT NULL
                          AND r.id IS NULL
                        ORDER BY o.completed_at DESC, oi.id DESC
                        LIMIT 50
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .param("completedStatus", COMPLETED)
                .query((rs, rowNum) -> new ReviewableOrderItemResponse(
                        rs.getLong("order_item_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getLong("sku_id"),
                        rs.getString("spec_text"),
                        rs.getObject("completed_at", LocalDateTime.class)
                ))
                .list();
        return new ProductReviewEligibilityResponse(orderItems);
    }

    @Transactional
    public ProductReviewResponse create(
            AuthenticatedPrincipal principal,
            Long spuId,
            ProductReviewRequest request
    ) {
        long userId = requireAppUser(principal);
        requireExistingProduct(spuId);
        boolean eligible = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_item oi
                        JOIN shop_order o ON o.id = oi.order_id
                        WHERE oi.id = :orderItemId
                          AND oi.spu_id = :spuId
                          AND o.user_id = :userId
                          AND o.status = :completedStatus
                          AND o.completed_at IS NOT NULL
                        """)
                .param("orderItemId", request.orderItemId())
                .param("spuId", spuId)
                .param("userId", userId)
                .param("completedStatus", COMPLETED)
                .query(Long.class)
                .single() == 1L;
        if (!eligible) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_ELIGIBLE);
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcClient.sql("""
                            INSERT INTO product_review (
                                user_id, spu_id, order_item_id, rating, content,
                                anonymous, status, created_at, updated_at
                            ) VALUES (
                                :userId, :spuId, :orderItemId, :rating, :content,
                                :anonymous, :status, :now, :now
                            )
                            """)
                    .param("userId", userId)
                    .param("spuId", spuId)
                    .param("orderItemId", request.orderItemId())
                    .param("rating", request.rating())
                    .param("content", normalizeContent(request.content()))
                    .param("anonymous", Boolean.TRUE.equals(request.anonymous()))
                    .param("status", PUBLISHED)
                    .param("now", now)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_ALREADY_EXISTS);
        }
        Long reviewId = jdbcClient.sql("""
                        SELECT id FROM product_review WHERE order_item_id = :orderItemId
                        """)
                .param("orderItemId", request.orderItemId())
                .query(Long.class)
                .single();
        return ownedReview(userId, reviewId);
    }

    @Transactional
    public ProductReviewResponse update(
            AuthenticatedPrincipal principal,
            Long reviewId,
            ProductReviewUpdateRequest request
    ) {
        long userId = requireAppUser(principal);
        int updated = jdbcClient.sql("""
                        UPDATE product_review
                        SET rating = :rating,
                            content = :content,
                            anonymous = :anonymous,
                            updated_at = :updatedAt
                        WHERE id = :reviewId
                          AND user_id = :userId
                          AND status = :status
                        """)
                .param("rating", request.rating())
                .param("content", normalizeContent(request.content()))
                .param("anonymous", Boolean.TRUE.equals(request.anonymous()))
                .param("updatedAt", LocalDateTime.now())
                .param("reviewId", reviewId)
                .param("userId", userId)
                .param("status", PUBLISHED)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND);
        }
        return ownedReview(userId, reviewId);
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, Long reviewId) {
        long userId = requireAppUser(principal);
        int deleted = jdbcClient.sql("""
                        DELETE FROM product_review
                        WHERE id = :reviewId AND user_id = :userId
                        """)
                .param("reviewId", reviewId)
                .param("userId", userId)
                .update();
        if (deleted != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND);
        }
    }

    public AppProductReviewSummaryResponse summary(Long spuId) {
        SummaryRow row = jdbcClient.sql("""
                        SELECT COUNT(*) AS review_count,
                               COALESCE(AVG(rating), 0) AS average_rating,
                               COALESCE(SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END), 0) AS good_review_count
                        FROM product_review
                        WHERE spu_id = :spuId AND status = :status
                        """)
                .param("spuId", spuId)
                .param("status", PUBLISHED)
                .query((rs, rowNum) -> new SummaryRow(
                        rs.getLong("review_count"),
                        rs.getBigDecimal("average_rating"),
                        rs.getLong("good_review_count")
                ))
                .single();
        BigDecimal average = row.averageRating() == null
                ? BigDecimal.ZERO.setScale(1)
                : row.averageRating().setScale(1, RoundingMode.HALF_UP);
        return new AppProductReviewSummaryResponse(row.reviewCount(), average, row.goodReviewCount());
    }

    private ProductReviewResponse ownedReview(long userId, Long reviewId) {
        return jdbcClient.sql("""
                        SELECT r.id, r.spu_id, p.title AS product_title, r.order_item_id,
                               oi.spec_text, r.rating, r.content, r.anonymous,
                               CASE WHEN r.anonymous = TRUE THEN '匿名用户'
                                    WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               r.created_at, r.updated_at
                        FROM product_review r
                        JOIN app_user u ON u.id = r.user_id
                        JOIN product_spu p ON p.id = r.spu_id
                        JOIN order_item oi ON oi.id = r.order_item_id
                        WHERE r.id = :reviewId AND r.user_id = :userId
                        """)
                .param("reviewId", reviewId)
                .param("userId", userId)
                .query(this::mapReview)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND));
    }

    private ProductReviewResponse mapReview(ResultSet rs, int rowNum) throws SQLException {
        return new ProductReviewResponse(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getString("product_title"),
                rs.getLong("order_item_id"),
                rs.getString("spec_text"),
                rs.getInt("rating"),
                rs.getString("content"),
                rs.getBoolean("anonymous"),
                rs.getString("reviewer_name"),
                true,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private PublicProductReviewResponse mapPublicReview(ResultSet rs, int rowNum) throws SQLException {
        return new PublicProductReviewResponse(
                rs.getLong("id"),
                rs.getString("spec_text"),
                rs.getInt("rating"),
                rs.getString("content"),
                rs.getBoolean("anonymous"),
                rs.getString("reviewer_name"),
                true,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private long reviewCount(Long spuId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM product_review
                        WHERE spu_id = :spuId AND status = :status
                        """)
                .param("spuId", spuId)
                .param("status", PUBLISHED)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private void requireVisibleProduct(Long spuId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM product_spu p
                        JOIN product_category c ON c.id = p.category_id
                        WHERE p.id = :spuId
                          AND p.status = :status
                          AND p.deleted_at IS NULL
                          AND p.purged_at IS NULL
                          AND c.status = 'ENABLED'
                        """)
                .param("spuId", spuId)
                .param("status", ProductStatus.ON_SALE.name())
                .query(Long.class)
                .single();
        if (count == null || count != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    private void requireExistingProduct(Long spuId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM product_spu
                        WHERE id = :spuId AND purged_at IS NULL
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        if (count == null || count != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private ProductEngagementPageRequest normalized(ProductEngagementPageRequest request) {
        return request == null ? new ProductEngagementPageRequest(null, null) : request;
    }

    private String normalizeContent(String content) {
        return content == null ? "" : content.trim();
    }

    private record SummaryRow(long reviewCount, BigDecimal averageRating, long goodReviewCount) {
    }
}
