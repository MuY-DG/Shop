package org.muybaby.shopserver.product.engagement.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.engagement.dto.DeleteBrowseHistoryItemsRequest;
import org.muybaby.shopserver.product.engagement.dto.DeleteFavoriteItemsRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductBrowseHistoryItemResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductBrowseHistoryPageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductBrowseRecordResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductEngagementPageRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductFavoriteItemResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductFavoriteStatusResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AppProductPreferenceService {

    private final JdbcClient jdbcClient;

    public AppProductPreferenceService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ProductFavoriteStatusResponse favoriteStatus(
            AuthenticatedPrincipal principal,
            Long spuId
    ) {
        long userId = requireAppUser(principal);
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM user_product_favorite
                        WHERE user_id = :userId AND spu_id = :spuId
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        return new ProductFavoriteStatusResponse(spuId, count != null && count > 0);
    }

    @Transactional
    public ProductFavoriteStatusResponse addFavorite(
            AuthenticatedPrincipal principal,
            Long spuId
    ) {
        long userId = requireAppUser(principal);
        requireVisibleProduct(spuId);
        try {
            jdbcClient.sql("""
                            INSERT INTO user_product_favorite (user_id, spu_id, created_at)
                            VALUES (:userId, :spuId, :createdAt)
                            """)
                    .param("userId", userId)
                    .param("spuId", spuId)
                    .param("createdAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException ignored) {
            // 收藏操作幂等，重复请求保持已收藏状态。
        }
        return new ProductFavoriteStatusResponse(spuId, true);
    }

    @Transactional
    public void removeFavorite(AuthenticatedPrincipal principal, Long spuId) {
        long userId = requireAppUser(principal);
        jdbcClient.sql("""
                        DELETE FROM user_product_favorite
                        WHERE user_id = :userId AND spu_id = :spuId
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .update();
    }

    @Transactional
    public void removeFavorites(
            AuthenticatedPrincipal principal,
            DeleteFavoriteItemsRequest request
    ) {
        long userId = requireAppUser(principal);
        LinkedHashSet<Long> spuIds = new LinkedHashSet<>(request.spuIds());
        jdbcClient.sql("""
                        DELETE FROM user_product_favorite
                        WHERE user_id = :userId AND spu_id IN (:spuIds)
                        """)
                .param("userId", userId)
                .param("spuIds", spuIds)
                .update();
    }

    public PageResult<ProductFavoriteItemResponse> favorites(
            AuthenticatedPrincipal principal,
            ProductEngagementPageRequest request
    ) {
        long userId = requireAppUser(principal);
        ProductEngagementPageRequest normalized = normalized(request);
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        long total = preferenceCount("user_product_favorite", userId);
        List<ProductFavoriteItemResponse> records = jdbcClient.sql("""
                        SELECT f.spu_id, p.title, p.subtitle, p.main_image,
                               (SELECT MIN(k.price_cent) FROM product_sku k
                                WHERE k.spu_id = p.id AND k.status = 'ENABLED' AND k.deleted_at IS NULL) AS min_price_cent,
                               (SELECT MAX(k.price_cent) FROM product_sku k
                                WHERE k.spu_id = p.id AND k.status = 'ENABLED' AND k.deleted_at IS NULL) AS max_price_cent,
                               CASE WHEN p.status = 'ON_SALE' AND p.deleted_at IS NULL
                                          AND p.purged_at IS NULL AND c.status = 'ENABLED'
                                    THEN TRUE ELSE FALSE END AS available,
                               f.created_at
                        FROM user_product_favorite f
                        JOIN product_spu p ON p.id = f.spu_id
                        JOIN product_category c ON c.id = p.category_id
                        WHERE f.user_id = :userId
                          AND p.purged_at IS NULL
                        ORDER BY f.created_at DESC, f.spu_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("userId", userId)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapFavorite)
                .list();
        return PageResult.of(records, total, current, size);
    }

    @Transactional
    public ProductBrowseRecordResponse recordBrowse(
            AuthenticatedPrincipal principal,
            Long spuId
    ) {
        long userId = requireAppUser(principal);
        requireVisibleProduct(spuId);
        LocalDateTime viewedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        int updated = incrementBrowse(userId, spuId, viewedAt);
        if (updated == 0) {
            try {
                jdbcClient.sql("""
                                INSERT INTO user_product_browse_history (
                                    user_id, spu_id, first_viewed_at, last_viewed_at, view_count
                                ) VALUES (:userId, :spuId, :viewedAt, :viewedAt, 1)
                                """)
                        .param("userId", userId)
                        .param("spuId", spuId)
                        .param("viewedAt", viewedAt)
                        .update();
            } catch (DuplicateKeyException ignored) {
                incrementBrowse(userId, spuId, viewedAt);
            }
        }
        return jdbcClient.sql("""
                        SELECT spu_id, last_viewed_at, view_count
                        FROM user_product_browse_history
                        WHERE user_id = :userId AND spu_id = :spuId
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new ProductBrowseRecordResponse(
                        rs.getLong("spu_id"),
                        rs.getObject("last_viewed_at", LocalDateTime.class),
                        rs.getLong("view_count")
                ))
                .single();
    }

    public ProductBrowseHistoryPageResponse browseHistory(
            AuthenticatedPrincipal principal,
            ProductEngagementPageRequest request
    ) {
        long userId = requireAppUser(principal);
        ProductEngagementPageRequest normalized = normalized(request);
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        long fetchSize = size + 1;
        List<ProductBrowseHistoryItemResponse> records = jdbcClient.sql("""
                        SELECT h.spu_id, p.title, p.subtitle, p.main_image,
                               (SELECT MIN(k.price_cent) FROM product_sku k
                                WHERE k.spu_id = p.id AND k.status = 'ENABLED' AND k.deleted_at IS NULL) AS min_price_cent,
                               (SELECT MAX(k.price_cent) FROM product_sku k
                                WHERE k.spu_id = p.id AND k.status = 'ENABLED' AND k.deleted_at IS NULL) AS max_price_cent,
                               CASE WHEN p.status = 'ON_SALE' AND p.deleted_at IS NULL
                                          AND p.purged_at IS NULL AND c.status = 'ENABLED'
                                    THEN TRUE ELSE FALSE END AS available,
                               h.first_viewed_at, h.last_viewed_at, h.view_count
                        FROM user_product_browse_history h
                        JOIN product_spu p ON p.id = h.spu_id
                        JOIN product_category c ON c.id = p.category_id
                        WHERE h.user_id = :userId
                          AND p.purged_at IS NULL
                        ORDER BY h.last_viewed_at DESC, h.spu_id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("userId", userId)
                .param("limit", fetchSize)
                .param("offset", offset)
                .query(this::mapHistory)
                .list();
        boolean hasMore = records.size() > size;
        List<ProductBrowseHistoryItemResponse> pageRecords = hasMore
                ? List.copyOf(records.subList(0, Math.toIntExact(size)))
                : records;
        return new ProductBrowseHistoryPageResponse(pageRecords, current, size, hasMore);
    }

    @Transactional
    public void deleteBrowse(AuthenticatedPrincipal principal, Long spuId) {
        long userId = requireAppUser(principal);
        jdbcClient.sql("""
                        DELETE FROM user_product_browse_history
                        WHERE user_id = :userId AND spu_id = :spuId
                        """)
                .param("userId", userId)
                .param("spuId", spuId)
                .update();
    }

    @Transactional
    public void deleteBrowseBatch(
            AuthenticatedPrincipal principal,
            DeleteBrowseHistoryItemsRequest request
    ) {
        long userId = requireAppUser(principal);
        LinkedHashSet<Long> spuIds = new LinkedHashSet<>(request.spuIds());
        jdbcClient.sql("""
                        DELETE FROM user_product_browse_history
                        WHERE user_id = :userId AND spu_id IN (:spuIds)
                        """)
                .param("userId", userId)
                .param("spuIds", spuIds)
                .update();
    }

    @Transactional
    public void clearBrowseHistory(AuthenticatedPrincipal principal) {
        long userId = requireAppUser(principal);
        jdbcClient.sql("DELETE FROM user_product_browse_history WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    private int incrementBrowse(long userId, Long spuId, LocalDateTime viewedAt) {
        return jdbcClient.sql("""
                        UPDATE user_product_browse_history
                        SET last_viewed_at = :viewedAt,
                            view_count = view_count + 1
                        WHERE user_id = :userId AND spu_id = :spuId
                        """)
                .param("viewedAt", viewedAt)
                .param("userId", userId)
                .param("spuId", spuId)
                .update();
    }

    private ProductFavoriteItemResponse mapFavorite(ResultSet rs, int rowNum) throws SQLException {
        return new ProductFavoriteItemResponse(
                rs.getLong("spu_id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getObject("min_price_cent", Long.class),
                rs.getObject("max_price_cent", Long.class),
                rs.getBoolean("available"),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private ProductBrowseHistoryItemResponse mapHistory(ResultSet rs, int rowNum) throws SQLException {
        return new ProductBrowseHistoryItemResponse(
                rs.getLong("spu_id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getObject("min_price_cent", Long.class),
                rs.getObject("max_price_cent", Long.class),
                rs.getBoolean("available"),
                rs.getObject("first_viewed_at", LocalDateTime.class),
                rs.getObject("last_viewed_at", LocalDateTime.class),
                rs.getLong("view_count")
        );
    }

    private long preferenceCount(String table, long userId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM %s pref
                        JOIN product_spu p ON p.id = pref.spu_id
                        WHERE pref.user_id = :userId AND p.purged_at IS NULL
                        """.formatted(table))
                .param("userId", userId)
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

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private ProductEngagementPageRequest normalized(ProductEngagementPageRequest request) {
        return request == null ? new ProductEngagementPageRequest(null, null) : request;
    }
}
