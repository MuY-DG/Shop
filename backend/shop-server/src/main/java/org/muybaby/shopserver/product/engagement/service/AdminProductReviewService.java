package org.muybaby.shopserver.product.engagement.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.engagement.ProductReviewStatus;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewQueryRequest;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminProductReviewService {

    private static final String REVIEW_FROM = """
            FROM product_review r
            JOIN app_user u ON u.id = r.user_id
            JOIN product_spu p ON p.id = r.spu_id
            JOIN order_item oi ON oi.id = r.order_item_id
            JOIN shop_order o ON o.id = oi.order_id
            """;

    private final JdbcClient jdbcClient;

    public AdminProductReviewService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
                        SELECT r.id, r.spu_id, p.title AS product_title, p.main_image AS product_image,
                               r.user_id,
                               CASE WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               o.id AS order_id, o.order_no, r.order_item_id, oi.spec_text,
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

    private String buildWhere(AdminProductReviewQueryRequest query, Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query.spuId() != null) {
            where.append(" AND r.spu_id = :spuId");
            parameters.put("spuId", query.spuId());
        }
        if (!query.normalizedProductTitle().isEmpty()) {
            where.append(" AND p.title LIKE CONCAT('%', :productTitle, '%')");
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
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("order_item_id"),
                rs.getString("spec_text"),
                rs.getInt("rating"),
                rs.getString("content"),
                rs.getBoolean("anonymous"),
                ProductReviewStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("moderated_by_admin_user_id", Long.class),
                rs.getObject("moderated_at", LocalDateTime.class)
        );
    }
}
