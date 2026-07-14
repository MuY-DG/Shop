package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.product.dto.AppCategoryResponse;
import org.muybaby.shopserver.product.dto.AppSkuResponse;
import org.muybaby.shopserver.product.dto.AppSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AppSpuListItemResponse;
import org.muybaby.shopserver.product.dto.ProductImageResponse;
import org.muybaby.shopserver.product.dto.ProductPageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@Service
public class AppProductService {

    private final JdbcClient jdbcClient;

    public AppProductService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AppCategoryResponse> categories() {
        return jdbcClient.sql("""
                        SELECT id, parent_id, name, icon, icon_file_id, sort_order, status
                        FROM product_category
                        WHERE status = :status
                        ORDER BY parent_id, sort_order, id
                        """)
                .param("status", "ENABLED")
                .query(this::mapCategory)
                .list();
    }

    public PageResult<AppSpuListItemResponse> page(ProductPageRequest request) {
        ProductPageRequest normalizedRequest = request == null ? new ProductPageRequest(null, null, null, null) : request;
        long current = normalizedRequest.pageCurrent();
        long size = normalizedRequest.pageSize();
        long offset = (current - 1) * size;
        String keywordLike = likeKeyword(normalizedRequest.keyword());

        Long total = jdbcClient.sql("""
                        SELECT count(*)
                        FROM product_spu s
                        JOIN product_category c ON c.id = s.category_id
                        WHERE s.status = :status
                          AND s.deleted_at IS NULL
                          AND c.status = :categoryStatus
                          AND (:categoryId IS NULL OR s.category_id = :categoryId)
                          AND (:keywordLike IS NULL OR s.title LIKE :keywordLike)
                        """)
                .param("status", ProductStatus.ON_SALE.name())
                .param("categoryStatus", "ENABLED")
                .param("categoryId", normalizedRequest.categoryId())
                .param("keywordLike", keywordLike)
                .query(Long.class)
                .single();

        List<AppSpuListItemResponse> records = jdbcClient.sql("""
                        SELECT s.id, s.category_id, s.title, s.subtitle, s.main_image, s.selling_points,
                               min(k.price_cent) AS min_price_cent,
                               max(k.price_cent) AS max_price_cent,
                               coalesce(sum(k.stock_available), 0) AS total_stock
                        FROM product_spu s
                        JOIN product_category c ON c.id = s.category_id
                        LEFT JOIN product_sku k ON k.spu_id = s.id
                            AND k.status = :skuStatus AND k.deleted_at IS NULL
                        WHERE s.status = :spuStatus
                          AND s.deleted_at IS NULL
                          AND c.status = :categoryStatus
                          AND (:categoryId IS NULL OR s.category_id = :categoryId)
                          AND (:keywordLike IS NULL OR s.title LIKE :keywordLike)
                        GROUP BY s.id, s.category_id, s.title, s.subtitle, s.main_image, s.selling_points, s.sort_order
                        ORDER BY s.sort_order ASC, s.id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("skuStatus", SkuStatus.ENABLED.name())
                .param("spuStatus", ProductStatus.ON_SALE.name())
                .param("categoryStatus", "ENABLED")
                .param("categoryId", normalizedRequest.categoryId())
                .param("keywordLike", keywordLike)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapSpuListItem)
                .list();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    public AppSpuDetailResponse detail(Long spuId) {
        SpuDetailRow spu = jdbcClient.sql("""
                        SELECT s.id, s.category_id, c.name AS category_name, s.title, s.subtitle, s.main_image,
                               s.main_image_file_id,
                               s.selling_points, s.detail_html
                        FROM product_spu s
                        JOIN product_category c ON c.id = s.category_id
                        WHERE s.id = :spuId
                          AND s.status = :status
                          AND s.deleted_at IS NULL
                          AND c.status = :categoryStatus
                        """)
                .param("spuId", spuId)
                .param("status", ProductStatus.ON_SALE.name())
                .param("categoryStatus", "ENABLED")
                .query(this::mapSpuDetailRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));

        List<ProductImageResponse> images = jdbcClient.sql("""
                        SELECT id, url, file_id, sort_order
                        FROM product_spu_image
                        WHERE spu_id = :spuId
                        ORDER BY sort_order ASC, id ASC
                        """)
                .param("spuId", spuId)
                .query(this::mapProductImage)
                .list();
        if (images.isEmpty() && StringUtils.hasText(spu.mainImage())) {
            images = List.of(new ProductImageResponse(null, spu.mainImage(), spu.mainImageFileId(), 1));
        }

        List<AppSkuResponse> skus = jdbcClient.sql("""
                        SELECT id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               stock_available, weight_gram, image, image_file_id, status
                        FROM product_sku
                        WHERE spu_id = :spuId
                          AND status = :status
                          AND deleted_at IS NULL
                        ORDER BY sort_order ASC, id ASC
                        """)
                .param("spuId", spuId)
                .param("status", SkuStatus.ENABLED.name())
                .query(this::mapAppSku)
                .list();

        return new AppSpuDetailResponse(
                spu.id(),
                spu.categoryId(),
                spu.categoryName(),
                spu.title(),
                spu.subtitle(),
                spu.mainImage(),
                spu.mainImageFileId(),
                splitSellingPoints(spu.sellingPoints()),
                spu.detailHtml(),
                images,
                skus
        );
    }

    private AppCategoryResponse mapCategory(ResultSet rs, int rowNum) throws SQLException {
        return new AppCategoryResponse(
                rs.getLong("id"),
                rs.getLong("parent_id"),
                rs.getString("name"),
                rs.getString("icon"),
                rs.getObject("icon_file_id", Long.class),
                rs.getInt("sort_order"),
                rs.getString("status")
        );
    }

    private AppSpuListItemResponse mapSpuListItem(ResultSet rs, int rowNum) throws SQLException {
        return new AppSpuListItemResponse(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                splitSellingPoints(rs.getString("selling_points")),
                rs.getObject("min_price_cent", Long.class),
                rs.getObject("max_price_cent", Long.class),
                rs.getInt("total_stock")
        );
    }

    private SpuDetailRow mapSpuDetailRow(ResultSet rs, int rowNum) throws SQLException {
        return new SpuDetailRow(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getObject("main_image_file_id", Long.class),
                rs.getString("selling_points"),
                rs.getString("detail_html")
        );
    }

    private ProductImageResponse mapProductImage(ResultSet rs, int rowNum) throws SQLException {
        return new ProductImageResponse(
                rs.getLong("id"),
                rs.getString("url"),
                rs.getObject("file_id", Long.class),
                rs.getInt("sort_order")
        );
    }

    private AppSkuResponse mapAppSku(ResultSet rs, int rowNum) throws SQLException {
        return new AppSkuResponse(
                rs.getLong("id"),
                rs.getString("sku_code"),
                rs.getString("spec_json"),
                rs.getString("spec_text"),
                rs.getLong("price_cent"),
                rs.getLong("original_price_cent"),
                rs.getInt("stock_available"),
                rs.getObject("weight_gram", Integer.class),
                rs.getString("image"),
                rs.getObject("image_file_id", Long.class),
                rs.getString("status")
        );
    }

    private String likeKeyword(String keyword) {
        return StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
    }

    private List<String> splitSellingPoints(String sellingPoints) {
        if (!StringUtils.hasText(sellingPoints)) {
            return List.of();
        }
        return Arrays.stream(sellingPoints.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private record SpuDetailRow(
            Long id,
            Long categoryId,
            String categoryName,
            String title,
            String subtitle,
            String mainImage,
            Long mainImageFileId,
            String sellingPoints,
            String detailHtml
    ) {
    }
}
