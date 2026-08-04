package org.muybaby.shopserver.product.engagement.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.dto.AppProductReviewSummaryResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewEligibilityResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewImageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewPageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewPageRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.PublicProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.ReviewableOrderItemResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUploadProfile;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.service.DirectUploadService;
import org.muybaby.shopserver.storage.service.StorageService;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AppProductReviewService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final String COMPLETED = "COMPLETED";
    private static final String REVIEW_IMAGE_CONTEXT = "PRODUCT_REVIEW_ORDER_ITEM";
    private static final int MAX_REVIEW_IMAGES = 6;

    private final JdbcClient jdbcClient;
    private final StorageService storageService;
    private final DirectUploadService directUploadService;
    private final StorageUsageService storageUsageService;

    public AppProductReviewService(
            JdbcClient jdbcClient,
            StorageService storageService,
            DirectUploadService directUploadService,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.storageService = storageService;
        this.directUploadService = directUploadService;
        this.storageUsageService = storageUsageService;
    }

    public ProductReviewPageResponse page(Long spuId, ProductReviewPageRequest request) {
        requireVisibleProduct(spuId);
        ProductReviewPageRequest normalized = normalized(request);
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = publicReviewWhere(spuId, normalized, parameters);
        Long total = jdbcClient.sql("SELECT COUNT(*) FROM product_review r" + where)
                .params(parameters)
                .query(Long.class)
                .single();
        parameters.put("limit", size);
        parameters.put("offset", offset);
        List<PublicProductReviewResponse> records = jdbcClient.sql("""
                        SELECT r.id, r.spec_text_snapshot AS spec_text,
                               r.rating, r.content, r.anonymous,
                               CASE WHEN r.anonymous = TRUE THEN '匿名用户'
                                    WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               r.verified_purchase,
                               r.created_at, r.updated_at
                        FROM product_review r
                        JOIN app_user u ON u.id = r.user_id
                        """ + where + "\n" + publicReviewOrderBy(normalized) + "\n" + """
                        LIMIT :limit OFFSET :offset
                        """)
                .params(parameters)
                .query(this::mapPublicReview)
                .list();
        records = attachPublicImages(records);
        return new ProductReviewPageResponse(
                summary(spuId),
                PageResult.of(records, total == null ? 0 : total, current, size)
        );
    }

    public StorageAssetResponse uploadImage(
            AuthenticatedPrincipal principal,
            Long orderItemId,
            MultipartFile file
    ) {
        long userId = requireAppUser(principal);
        requirePendingReviewableOrderItem(userId, orderItemId);
        return storageService.uploadProductReviewImage(principal, orderItemId, file);
    }

    public DirectUploadSessionResponse createImageUploadSession(
            AuthenticatedPrincipal principal,
            Long orderItemId,
            DirectUploadSessionRequest request
    ) {
        long userId = requireAppUser(principal);
        requirePendingReviewableOrderItem(userId, orderItemId);
        return directUploadService.create(
                principal,
                StorageUploadProfile.PRODUCT_REVIEW_IMAGE,
                null,
                REVIEW_IMAGE_CONTEXT,
                orderItemId,
                request
        );
    }

    public StorageAssetResponse completeImageUploadSession(
            AuthenticatedPrincipal principal,
            Long orderItemId,
            String uploadId
    ) {
        long userId = requireAppUser(principal);
        requirePendingReviewableOrderItem(userId, orderItemId);
        return directUploadService.complete(
                principal,
                uploadId,
                StorageUploadProfile.PRODUCT_REVIEW_IMAGE,
                orderItemId
        ).asset();
    }

    public void cancelImageUploadSession(
            AuthenticatedPrincipal principal,
            Long orderItemId,
            String uploadId
    ) {
        long userId = requireAppUser(principal);
        requireOwnedOrderItem(userId, orderItemId);
        directUploadService.cancel(
                principal,
                uploadId,
                StorageUploadProfile.PRODUCT_REVIEW_IMAGE,
                orderItemId
        );
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
                        LEFT JOIN product_review r ON r.source_order_item_id = oi.id
                        WHERE o.user_id = :userId
                          AND oi.spu_id = :spuId
                          AND o.status = :completedStatus
                          AND o.completed_at IS NOT NULL
                          AND o.app_deleted_at IS NULL
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
        Long orderId = jdbcClient.sql("""
                        SELECT order_id
                        FROM order_item
                        WHERE id = :orderItemId AND spu_id = :spuId
                        """)
                .param("orderItemId", request.orderItemId())
                .param("spuId", spuId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_ELIGIBLE));
        boolean completedOrderLocked = jdbcClient.sql("""
                        SELECT id
                        FROM shop_order
                        WHERE id = :orderId
                          AND user_id = :userId
                          AND status = :completedStatus
                          AND completed_at IS NOT NULL
                          AND app_deleted_at IS NULL
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .param("completedStatus", COMPLETED)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!completedOrderLocked) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_ELIGIBLE);
        }
        ReviewSnapshot snapshot = jdbcClient.sql("""
                        SELECT oi.product_title, oi.spec_text
                        FROM order_item oi
                        WHERE oi.id = :orderItemId
                          AND oi.order_id = :orderId
                          AND oi.spu_id = :spuId
                        FOR UPDATE
                        """)
                .param("orderItemId", request.orderItemId())
                .param("orderId", orderId)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new ReviewSnapshot(
                        rs.getString("product_title"),
                        rs.getString("spec_text")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_ELIGIBLE));
        List<Long> imageFileIds = normalizeImageFileIds(request.imageFileIds());
        List<ReviewImageAsset> imageAssets = lockReviewImageAssets(
                userId, request.orderItemId(), imageFileIds);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        try {
            jdbcClient.sql("""
                            INSERT INTO product_review (
                                user_id, spu_id, source_order_item_id, order_item_id,
                                product_title_snapshot, spec_text_snapshot, verified_purchase,
                                rating, content, anonymous, status, created_at, updated_at
                            ) VALUES (
                                :userId, :spuId, :orderItemId, :orderItemId,
                                :productTitle, :specText, TRUE,
                                :rating, :content, :anonymous, :status, :now, :now
                            )
                            """)
                    .param("userId", userId)
                    .param("spuId", spuId)
                    .param("orderItemId", request.orderItemId())
                    .param("productTitle", snapshot.productTitle())
                    .param("specText", snapshot.specText())
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
                        SELECT id FROM product_review WHERE source_order_item_id = :orderItemId
                        """)
                .param("orderItemId", request.orderItemId())
                .query(Long.class)
                .single();
        bindReviewImages(reviewId, snapshot.productTitle(), imageAssets);
        return ownedReview(userId, reviewId);
    }

    public AppProductReviewSummaryResponse summary(Long spuId) {
        SummaryRow row = jdbcClient.sql("""
                        SELECT COUNT(*) AS review_count,
                               COALESCE(AVG(rating), 0) AS average_rating,
                               COALESCE(SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END), 0) AS good_review_count,
                               COALESCE(SUM(CASE WHEN rating <= 3 THEN 1 ELSE 0 END), 0) AS critical_review_count,
                               COALESCE(SUM(CASE WHEN EXISTS (
                                   SELECT 1 FROM product_review_image image
                                   WHERE image.review_id = review.id
                               ) THEN 1 ELSE 0 END), 0) AS image_review_count
                        FROM product_review review
                        WHERE review.spu_id = :spuId AND review.status = :status
                        """)
                .param("spuId", spuId)
                .param("status", PUBLISHED)
                .query((rs, rowNum) -> new SummaryRow(
                        rs.getLong("review_count"),
                        rs.getBigDecimal("average_rating"),
                        rs.getLong("good_review_count"),
                        rs.getLong("image_review_count"),
                        rs.getLong("critical_review_count")
                ))
                .single();
        BigDecimal average = row.averageRating() == null
                ? BigDecimal.ZERO.setScale(1)
                : row.averageRating().setScale(1, RoundingMode.HALF_UP);
        return new AppProductReviewSummaryResponse(
                row.reviewCount(),
                average,
                row.goodReviewCount(),
                row.imageReviewCount(),
                row.criticalReviewCount()
        );
    }

    private ProductReviewResponse ownedReview(long userId, Long reviewId) {
        ProductReviewResponse review = jdbcClient.sql("""
                        SELECT r.id, r.spu_id, r.product_title_snapshot AS product_title,
                               r.source_order_item_id AS order_item_id,
                               r.spec_text_snapshot AS spec_text,
                               r.rating, r.content, r.anonymous,
                               CASE WHEN r.anonymous = TRUE THEN '匿名用户'
                                    WHEN u.nickname <> '' THEN u.nickname
                                    ELSE CONCAT('用户', RIGHT(CONCAT('', u.id), 6)) END AS reviewer_name,
                               r.verified_purchase,
                               r.created_at, r.updated_at
                        FROM product_review r
                        JOIN app_user u ON u.id = r.user_id
                        WHERE r.id = :reviewId AND r.user_id = :userId
                        """)
                .param("reviewId", reviewId)
                .param("userId", userId)
                .query(this::mapReview)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_FOUND));
        return withImages(review, reviewImages(List.of(reviewId)).getOrDefault(reviewId, List.of()));
    }

    private ProductReviewResponse mapReview(ResultSet rs, int rowNum) throws SQLException {
        return new ProductReviewResponse(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getString("product_title"),
                rs.getObject("order_item_id", Long.class),
                rs.getString("spec_text"),
                rs.getInt("rating"),
                rs.getString("content"),
                rs.getBoolean("anonymous"),
                rs.getString("reviewer_name"),
                rs.getBoolean("verified_purchase"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                List.of()
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
                rs.getBoolean("verified_purchase"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                List.of()
        );
    }

    private List<Long> normalizeImageFileIds(List<Long> imageFileIds) {
        if (imageFileIds == null || imageFileIds.isEmpty()) {
            return List.of();
        }
        List<Long> normalized = new ArrayList<>();
        for (Long fileId : imageFileIds) {
            if (fileId == null || fileId <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (!normalized.contains(fileId)) {
                normalized.add(fileId);
            }
        }
        if (normalized.size() > MAX_REVIEW_IMAGES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return List.copyOf(normalized);
    }

    private List<ReviewImageAsset> lockReviewImageAssets(
            long userId,
            Long orderItemId,
            List<Long> imageFileIds
    ) {
        if (imageFileIds.isEmpty()) {
            return List.of();
        }
        List<ReviewImageAsset> rows = jdbcClient.sql("""
                        SELECT asset.id, asset.public_url
                        FROM storage_asset asset
                        WHERE asset.id IN (:fileIds)
                          AND asset.scope = 'LIBRARY'
                          AND asset.media_kind = 'IMAGE'
                          AND asset.visibility = 'PUBLIC'
                          AND asset.status = 'ACTIVE'
                          AND asset.uploaded_by_type = 'APP'
                          AND asset.uploaded_by_id = :userId
                          AND asset.upload_context_type = :contextType
                          AND asset.upload_context_id = :orderItemId
                          AND asset.public_url IS NOT NULL
                          AND asset.public_url <> ''
                          AND asset.expires_at > CURRENT_TIMESTAMP
                          AND NOT EXISTS (
                              SELECT 1 FROM storage_asset_usage usage_ref
                              WHERE usage_ref.asset_id = asset.id AND usage_ref.status = 'ACTIVE'
                          )
                        ORDER BY asset.id
                        FOR UPDATE
                        """)
                .param("fileIds", imageFileIds)
                .param("userId", userId)
                .param("contextType", REVIEW_IMAGE_CONTEXT)
                .param("orderItemId", orderItemId)
                .query((rs, rowNum) -> new ReviewImageAsset(
                        rs.getLong("id"), rs.getString("public_url")))
                .list();
        Map<Long, ReviewImageAsset> byId = rows.stream().collect(Collectors.toMap(
                ReviewImageAsset::fileId,
                Function.identity()
        ));
        if (byId.size() != imageFileIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return imageFileIds.stream().map(byId::get).toList();
    }

    private void bindReviewImages(
            Long reviewId,
            String productTitle,
            List<ReviewImageAsset> images
    ) {
        int sortOrder = 1;
        for (ReviewImageAsset image : images) {
            jdbcClient.sql("""
                            INSERT INTO product_review_image
                                (review_id, asset_id, image_url, sort_order)
                            VALUES (:reviewId, :assetId, :imageUrl, :sortOrder)
                            """)
                    .param("reviewId", reviewId)
                    .param("assetId", image.fileId())
                    .param("imageUrl", image.url())
                    .param("sortOrder", sortOrder)
                    .update();
            storageUsageService.addProtectedUsage(
                    image.fileId(),
                    StorageFileUsageType.PRODUCT_REVIEW_IMAGE,
                    StorageUsageOwnerType.PRODUCT_REVIEW,
                    reviewId,
                    "商品评价 " + reviewId + " / " + productTitle,
                    image.url(),
                    sortOrder
            );
            sortOrder++;
        }
        if (!images.isEmpty()) {
            int claimed = jdbcClient.sql("""
                            UPDATE storage_asset
                            SET expires_at = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id IN (:fileIds)
                              AND expires_at IS NOT NULL
                            """)
                    .param("fileIds", images.stream().map(ReviewImageAsset::fileId).toList())
                    .update();
            if (claimed != images.size()) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
        }
    }

    private Map<Long, List<ProductReviewImageResponse>> reviewImages(List<Long> reviewIds) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ProductReviewImageResponse>> images = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT review_id, asset_id, image_url, sort_order
                        FROM product_review_image
                        WHERE review_id IN (:reviewIds)
                        ORDER BY review_id, sort_order, id
                        """)
                .param("reviewIds", reviewIds)
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
        return images;
    }

    private List<PublicProductReviewResponse> attachPublicImages(
            List<PublicProductReviewResponse> reviews
    ) {
        Map<Long, List<ProductReviewImageResponse>> images = reviewImages(
                reviews.stream().map(PublicProductReviewResponse::id).toList());
        return reviews.stream()
                .map(review -> withImages(review, images.getOrDefault(review.id(), List.of())))
                .toList();
    }

    private ProductReviewResponse withImages(
            ProductReviewResponse review,
            List<ProductReviewImageResponse> images
    ) {
        return new ProductReviewResponse(
                review.id(), review.spuId(), review.productTitle(), review.orderItemId(),
                review.skuSpecText(), review.rating(), review.content(), review.anonymous(),
                review.reviewerName(), review.verifiedPurchase(), review.createdAt(),
                review.updatedAt(), List.copyOf(images)
        );
    }

    private PublicProductReviewResponse withImages(
            PublicProductReviewResponse review,
            List<ProductReviewImageResponse> images
    ) {
        return new PublicProductReviewResponse(
                review.id(), review.skuSpecText(), review.rating(), review.content(),
                review.anonymous(), review.reviewerName(), review.verifiedPurchase(),
                review.createdAt(), review.updatedAt(), List.copyOf(images)
        );
    }

    private void requirePendingReviewableOrderItem(long userId, Long orderItemId) {
        boolean reviewable = jdbcClient.sql("""
                        SELECT item.id
                        FROM order_item item
                        JOIN shop_order shop_order ON shop_order.id = item.order_id
                        WHERE item.id = :orderItemId
                          AND shop_order.user_id = :userId
                          AND shop_order.status = :status
                          AND shop_order.completed_at IS NOT NULL
                          AND shop_order.app_deleted_at IS NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM product_review review
                              WHERE review.source_order_item_id = item.id
                          )
                        """)
                .param("orderItemId", orderItemId)
                .param("userId", userId)
                .param("status", COMPLETED)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!reviewable) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_ELIGIBLE);
        }
    }

    private void requireOwnedOrderItem(long userId, Long orderItemId) {
        boolean owned = jdbcClient.sql("""
                        SELECT item.id
                        FROM order_item item
                        JOIN shop_order shop_order ON shop_order.id = item.order_id
                        WHERE item.id = :orderItemId AND shop_order.user_id = :userId
                        """)
                .param("orderItemId", orderItemId)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!owned) {
            throw new BusinessException(ErrorCode.PRODUCT_REVIEW_NOT_ELIGIBLE);
        }
    }

    private String publicReviewWhere(
            Long spuId,
            ProductReviewPageRequest request,
            Map<String, Object> parameters
    ) {
        StringBuilder where = new StringBuilder("""
                 WHERE r.spu_id = :spuId
                   AND r.status = :status
                """);
        parameters.put("spuId", spuId);
        parameters.put("status", PUBLISHED);
        switch (request.pageFilter()) {
            case WITH_IMAGES -> where.append("""
                     AND EXISTS (
                         SELECT 1 FROM product_review_image image
                         WHERE image.review_id = r.id
                     )
                    """);
            case GOOD -> where.append(" AND r.rating >= 4");
            case CRITICAL -> where.append(" AND r.rating <= 3");
            case ALL -> {
                // No additional rating or media filter.
            }
        }
        if (!request.normalizedSpecText().isEmpty()) {
            where.append(" AND r.spec_text_snapshot = :specText");
            parameters.put("specText", request.normalizedSpecText());
        }
        return where.toString();
    }

    private String publicReviewOrderBy(ProductReviewPageRequest request) {
        return switch (request.pageSort()) {
            case LATEST -> """
                    ORDER BY CASE WHEN r.rating >= 4 THEN 0 ELSE 1 END,
                             r.created_at DESC,
                             r.id DESC
                    """;
            case RECOMMENDED -> """
                    ORDER BY CASE WHEN r.rating >= 4 THEN 0 ELSE 1 END,
                             CASE WHEN EXISTS (
                                 SELECT 1 FROM product_review_image image
                                 WHERE image.review_id = r.id
                             ) THEN 0 ELSE 1 END,
                             r.rating DESC,
                             r.created_at DESC,
                             r.id DESC
                    """;
        };
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

    private ProductReviewPageRequest normalized(ProductReviewPageRequest request) {
        return request == null
                ? new ProductReviewPageRequest(null, null, null, null, null)
                : request;
    }

    private String normalizeContent(String content) {
        return content == null ? "" : content.trim();
    }

    private record SummaryRow(
            long reviewCount,
            BigDecimal averageRating,
            long goodReviewCount,
            long imageReviewCount,
            long criticalReviewCount
    ) {
    }

    private record ReviewSnapshot(String productTitle, String specText) {
    }

    private record ReviewImageAsset(Long fileId, String url) {
    }

    private record ReviewImageProjection(Long reviewId, ProductReviewImageResponse image) {
    }
}
