package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.FreightChargeMode;
import org.muybaby.shopserver.product.FreightTemplateStatus;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.product.dto.AppCategoryResponse;
import org.muybaby.shopserver.product.dto.AppFreightTemplateResponse;
import org.muybaby.shopserver.product.dto.AppGuaranteeServiceResponse;
import org.muybaby.shopserver.product.dto.AppProductParameterValueResponse;
import org.muybaby.shopserver.product.dto.AppSkuResponse;
import org.muybaby.shopserver.product.dto.AppSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AppSpuListItemResponse;
import org.muybaby.shopserver.product.dto.ProductImageResponse;
import org.muybaby.shopserver.product.dto.WholesaleTierResponse;
import org.muybaby.shopserver.product.dto.ProductPageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class AppProductService {

    private final JdbcClient jdbcClient;
    private final ProductParameterService productParameterService;

    public AppProductService(JdbcClient jdbcClient, ProductParameterService productParameterService) {
        this.jdbcClient = jdbcClient;
        this.productParameterService = productParameterService;
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

        List<SpuListRow> rows = jdbcClient.sql("""
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
                .query(this::mapSpuListRow)
                .list();
        Map<Long, List<AppProductParameterValueResponse>> parametersBySpuId =
                productParameterService.displayValuesBySpuIds(
                        rows.stream().map(SpuListRow::id).toList(),
                        true
                );
        List<AppSpuListItemResponse> records = rows.stream()
                .map(row -> new AppSpuListItemResponse(
                        row.id(),
                        row.categoryId(),
                        row.title(),
                        row.subtitle(),
                        row.mainImage(),
                        splitSellingPoints(row.sellingPoints()),
                        row.minPriceCent(),
                        row.maxPriceCent(),
                        row.totalStock(),
                        parametersBySpuId.getOrDefault(row.id(), List.of())
                ))
                .toList();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    public AppSpuDetailResponse detail(Long spuId) {
        SpuDetailRow spu = jdbcClient.sql("""
                        SELECT s.id, s.category_id, c.name AS category_name, s.title, s.subtitle, s.main_image,
                               s.main_image_file_id,
                               s.virtual_sales + COALESCE((
                                   SELECT SUM(oi.quantity)
                                   FROM order_item oi
                                   JOIN shop_order o ON o.id = oi.order_id
                                   WHERE oi.spu_id = s.id AND o.paid_at IS NOT NULL
                               ), 0) AS sales_count,
                               s.selling_points, s.detail_html,
                               f.id AS freight_template_id, f.name AS freight_template_name,
                               f.charge_mode AS freight_charge_mode,
                               f.fixed_amount_cent AS freight_fixed_amount_cent
                        FROM product_spu s
                        JOIN product_category c ON c.id = s.category_id
                        JOIN freight_template f ON f.id = s.freight_template_id
                            AND f.status = :freightStatus
                            AND f.deleted_at IS NULL
                        WHERE s.id = :spuId
                          AND s.status = :status
                          AND s.deleted_at IS NULL
                          AND c.status = :categoryStatus
                        """)
                .param("spuId", spuId)
                .param("status", ProductStatus.ON_SALE.name())
                .param("categoryStatus", "ENABLED")
                .param("freightStatus", FreightTemplateStatus.ENABLED.name())
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
        Map<Long, List<WholesaleTierResponse>> wholesaleTiersBySkuId = findWholesaleTiers(spuId);
        skus = skus.stream()
                .map(sku -> new AppSkuResponse(
                        sku.id(), sku.skuCode(), sku.specJson(), sku.specText(), sku.priceCent(),
                        sku.originalPriceCent(), sku.stockAvailable(), sku.weightGram(), sku.image(),
                        sku.imageFileId(), sku.status(),
                        wholesaleTiersBySkuId.getOrDefault(sku.id(), List.of())
                ))
                .toList();
        List<AppGuaranteeServiceResponse> guaranteeServices = findGuaranteeServices(spuId);

        return new AppSpuDetailResponse(
                spu.id(),
                spu.categoryId(),
                spu.categoryName(),
                spu.title(),
                spu.subtitle(),
                spu.mainImage(),
                spu.mainImageFileId(),
                spu.salesCount(),
                splitSellingPoints(spu.sellingPoints()),
                spu.detailHtml(),
                images,
                skus,
                productParameterService.displayValues(spuId, false),
                spu.freightTemplate(),
                guaranteeServices
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

    private SpuListRow mapSpuListRow(ResultSet rs, int rowNum) throws SQLException {
        return new SpuListRow(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getString("selling_points"),
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
                rs.getLong("sales_count"),
                rs.getString("selling_points"),
                rs.getString("detail_html"),
                new AppFreightTemplateResponse(
                        rs.getLong("freight_template_id"),
                        rs.getString("freight_template_name"),
                        FreightChargeMode.valueOf(rs.getString("freight_charge_mode")),
                        rs.getLong("freight_fixed_amount_cent")
                )
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
                rs.getString("status"),
                List.of()
        );
    }

    private Map<Long, List<WholesaleTierResponse>> findWholesaleTiers(Long spuId) {
        List<AppWholesaleTierRow> rows = jdbcClient.sql("""
                        select t.sku_id, t.min_quantity, t.unit_price_cent
                        from product_sku_wholesale_tier t
                        join product_sku k on k.id = t.sku_id
                        where k.spu_id = :spuId
                          and k.status = :status
                          and k.deleted_at is null
                        order by t.sku_id, t.min_quantity, t.id
                        """)
                .param("spuId", spuId)
                .param("status", SkuStatus.ENABLED.name())
                .query((rs, rowNum) -> new AppWholesaleTierRow(
                        rs.getLong("sku_id"),
                        new WholesaleTierResponse(
                                rs.getInt("min_quantity"),
                                rs.getLong("unit_price_cent")
                        )
                ))
                .list();
        Map<Long, List<WholesaleTierResponse>> result = new java.util.LinkedHashMap<>();
        for (AppWholesaleTierRow row : rows) {
            result.computeIfAbsent(row.skuId(), ignored -> new java.util.ArrayList<>()).add(row.tier());
        }
        return result;
    }

    private List<AppGuaranteeServiceResponse> findGuaranteeServices(Long spuId) {
        return jdbcClient.sql("""
                        select s.id, s.terms_name, s.content_description, s.icon, s.icon_file_id,
                               binding.sort_order
                        from product_spu_guarantee_service binding
                        join product_guarantee_service s on s.id = binding.service_id
                        where binding.spu_id = :spuId
                          and s.visible = true
                          and s.deleted_at is null
                        order by binding.sort_order asc, s.sort_order asc, s.id asc
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new AppGuaranteeServiceResponse(
                        rs.getLong("id"),
                        rs.getString("terms_name"),
                        rs.getString("content_description"),
                        rs.getString("icon"),
                        rs.getObject("icon_file_id", Long.class),
                        rs.getInt("sort_order")
                ))
                .list();
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
            Long salesCount,
            String sellingPoints,
            String detailHtml,
            AppFreightTemplateResponse freightTemplate
    ) {
    }

    private record SpuListRow(
            Long id,
            Long categoryId,
            String title,
            String subtitle,
            String mainImage,
            String sellingPoints,
            Long minPriceCent,
            Long maxPriceCent,
            Integer totalStock
    ) {
    }

    private record AppWholesaleTierRow(Long skuId, WholesaleTierResponse tier) {
    }
}
