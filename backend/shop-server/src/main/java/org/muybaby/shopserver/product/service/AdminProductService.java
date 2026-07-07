package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.CategoryStatus;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.entity.ProductCategory;
import org.muybaby.shopserver.product.entity.ProductSku;
import org.muybaby.shopserver.product.entity.ProductSpu;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminProductService {

    private static final String SYSTEM_OPERATOR_TYPE = "SYSTEM";
    private static final long SYSTEM_OPERATOR_ID = 0L;
    private static final String ADMIN_OPERATOR_TYPE = "ADMIN";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public AdminProductService(JdbcClient jdbcClient, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Transactional
    public Long createCategory(AdminCategoryRequest request) {
        String status = requireCategoryStatus(request.status()).name();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_category (parent_id, name, icon, sort_order, status)
                        VALUES (:parentId, :name, :icon, :sortOrder, :status)
                        """,
                new MapSqlParameterSource()
                        .addValue("parentId", request.parentId())
                        .addValue("name", request.name())
                        .addValue("icon", defaultString(request.icon()))
                        .addValue("sortOrder", request.sortOrder())
                        .addValue("status", status),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    @Transactional
    public void updateCategory(Long categoryId, AdminCategoryRequest request) {
        String status = requireCategoryStatus(request.status()).name();
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_category
                        SET parent_id = :parentId,
                            name = :name,
                            icon = :icon,
                            sort_order = :sortOrder,
                            status = :status,
                            updated_at = :updatedAt
                        WHERE id = :categoryId
                        """)
                .param("parentId", request.parentId())
                .param("name", request.name())
                .param("icon", defaultString(request.icon()))
                .param("sortOrder", request.sortOrder())
                .param("status", status)
                .param("updatedAt", LocalDateTime.now())
                .param("categoryId", categoryId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE);
        }
    }

    @Transactional
    public Long createSpu(AdminSpuUpsertRequest request) {
        requireExistingCategory(request.categoryId());
        Long spuId = insertSpu(request);
        replaceImageRows(spuId, request.images());
        replaceSkuRows(spuId, request.skus(), true);
        return spuId;
    }

    @Transactional
    public void updateSpu(Long spuId, AdminSpuUpsertRequest request) {
        requireExistingCategory(request.categoryId());
        ProductSpu existingSpu = findSpu(spuId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET category_id = :categoryId,
                            title = :title,
                            subtitle = :subtitle,
                            main_image = :mainImage,
                            selling_points = :sellingPoints,
                            detail_html = :detailHtml,
                            sort_order = :sortOrder,
                            status = :status,
                            updated_at = :updatedAt
                        WHERE id = :spuId
                        """)
                .param("categoryId", request.categoryId())
                .param("title", request.title())
                .param("subtitle", defaultString(request.subtitle()))
                .param("mainImage", request.mainImage())
                .param("sellingPoints", defaultString(request.sellingPoints()))
                .param("detailHtml", defaultString(request.detailHtml()))
                .param("sortOrder", request.sortOrder())
                .param("status", existingSpu.status())
                .param("updatedAt", LocalDateTime.now())
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        replaceImageRows(spuId, request.images());
        replaceSkuRows(spuId, request.skus(), false);
    }

    @Transactional
    public void publishSpu(Long spuId) {
        ProductSpu spu = findSpu(spuId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        ProductCategory category = findCategory(spu.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        boolean hasPublishableSku = findSkusBySpuId(spuId).stream()
                .anyMatch(sku -> SkuStatus.ENABLED.name().equals(sku.status()) && sku.priceCent() != null && sku.priceCent() > 0);
        if (!CategoryStatus.ENABLED.name().equals(category.status())
                || !StringUtils.hasText(spu.title())
                || !StringUtils.hasText(spu.mainImage())
                || !hasPublishableSku) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        jdbcClient.sql("""
                        UPDATE product_spu
                        SET status = :status, updated_at = :updatedAt
                        WHERE id = :spuId
                        """)
                .param("status", ProductStatus.ON_SALE.name())
                .param("updatedAt", LocalDateTime.now())
                .param("spuId", spuId)
                .update();
    }

    @Transactional
    public void unpublishSpu(Long spuId) {
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET status = :status, updated_at = :updatedAt
                        WHERE id = :spuId
                        """)
                .param("status", ProductStatus.OFF_SALE.name())
                .param("updatedAt", LocalDateTime.now())
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    @Transactional
    public void adjustSkuStock(Long skuId, AdminStockAdjustmentRequest request, Long operatorId) {
        ProductSku sku = findSku(skuId).orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
        int quantityBefore = sku.stockAvailable();
        int quantityAfter = quantityBefore + request.quantityDelta();
        if (quantityAfter < 0) {
            throw new BusinessException(ErrorCode.STOCK_SHORTAGE);
        }
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_sku
                        SET stock_available = :stockAvailable,
                            updated_at = :updatedAt
                        WHERE id = :skuId
                        """)
                .param("stockAvailable", quantityAfter)
                .param("updatedAt", LocalDateTime.now())
                .param("skuId", skuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        insertStockLog(skuId, StockChangeType.ADJUST.name(), quantityBefore, request.quantityDelta(), quantityAfter, request.reason(), ADMIN_OPERATOR_TYPE, operatorId);
    }

    private Long insertSpu(AdminSpuUpsertRequest request) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_spu (
                            category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status
                        )
                        VALUES (
                            :categoryId, :title, :subtitle, :mainImage, :sellingPoints, :detailHtml, :sortOrder, :status
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("categoryId", request.categoryId())
                        .addValue("title", request.title())
                        .addValue("subtitle", defaultString(request.subtitle()))
                        .addValue("mainImage", request.mainImage())
                        .addValue("sellingPoints", defaultString(request.sellingPoints()))
                        .addValue("detailHtml", defaultString(request.detailHtml()))
                        .addValue("sortOrder", request.sortOrder())
                        .addValue("status", ProductStatus.DRAFT.name()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void replaceImageRows(Long spuId, List<String> images) {
        jdbcClient.sql("DELETE FROM product_spu_image WHERE spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        List<String> normalizedImages = images == null ? List.of() : images;
        for (int index = 0; index < normalizedImages.size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO product_spu_image (spu_id, url, sort_order)
                            VALUES (:spuId, :url, :sortOrder)
                            """)
                    .param("spuId", spuId)
                    .param("url", normalizedImages.get(index))
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private void replaceSkuRows(Long spuId, List<AdminSkuUpsertRequest> skus, boolean writeInitialLog) {
        jdbcClient.sql("DELETE FROM product_sku WHERE spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        List<AdminSkuUpsertRequest> normalizedSkus = skus == null ? List.of() : skus;
        for (AdminSkuUpsertRequest sku : normalizedSkus) {
            Long skuId = insertSku(spuId, sku);
            if (writeInitialLog) {
                insertStockLog(
                        skuId,
                        StockChangeType.INITIAL.name(),
                        0,
                        sku.stockAvailable(),
                        sku.stockAvailable(),
                        "initial stock",
                        SYSTEM_OPERATOR_TYPE,
                        SYSTEM_OPERATOR_ID
                );
            }
        }
    }

    private Long insertSku(Long spuId, AdminSkuUpsertRequest request) {
        String status = requireSkuStatus(request.status()).name();
        if (request.id() != null) {
            jdbcClient.sql("""
                            INSERT INTO product_sku (
                                id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                                stock_available, weight_gram, image, status, sort_order
                            )
                            VALUES (
                                :id, :spuId, :skuCode, :specJson, :specText, :priceCent, :originalPriceCent,
                                :stockAvailable, :weightGram, :image, :status, :sortOrder
                            )
                            """)
                    .param("id", request.id())
                    .param("spuId", spuId)
                    .param("skuCode", request.skuCode())
                    .param("specJson", request.specJson())
                    .param("specText", request.specText())
                    .param("priceCent", request.priceCent())
                    .param("originalPriceCent", request.originalPriceCent())
                    .param("stockAvailable", request.stockAvailable())
                    .param("weightGram", request.weightGram())
                    .param("image", defaultString(request.image()))
                    .param("status", status)
                    .param("sortOrder", request.sortOrder())
                    .update();
            return request.id();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_sku (
                            spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                            stock_available, weight_gram, image, status, sort_order
                        )
                        VALUES (
                            :spuId, :skuCode, :specJson, :specText, :priceCent, :originalPriceCent,
                            :stockAvailable, :weightGram, :image, :status, :sortOrder
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("spuId", spuId)
                        .addValue("skuCode", request.skuCode())
                        .addValue("specJson", request.specJson())
                        .addValue("specText", request.specText())
                        .addValue("priceCent", request.priceCent())
                        .addValue("originalPriceCent", request.originalPriceCent())
                        .addValue("stockAvailable", request.stockAvailable())
                        .addValue("weightGram", request.weightGram())
                        .addValue("image", defaultString(request.image()))
                        .addValue("status", status)
                        .addValue("sortOrder", request.sortOrder()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void insertStockLog(
            Long skuId,
            String changeType,
            Integer quantityBefore,
            Integer quantityDelta,
            Integer quantityAfter,
            String reason,
            String operatorType,
            Long operatorId
    ) {
        jdbcClient.sql("""
                        INSERT INTO stock_log (
                            sku_id, change_type, quantity_before, quantity_delta, quantity_after, reason, operator_type, operator_id
                        )
                        VALUES (
                            :skuId, :changeType, :quantityBefore, :quantityDelta, :quantityAfter, :reason, :operatorType, :operatorId
                        )
                        """)
                .param("skuId", skuId)
                .param("changeType", changeType)
                .param("quantityBefore", quantityBefore)
                .param("quantityDelta", quantityDelta)
                .param("quantityAfter", quantityAfter)
                .param("reason", reason)
                .param("operatorType", operatorType)
                .param("operatorId", operatorId)
                .update();
    }

    private ProductCategory requireExistingCategory(Long categoryId) {
        return findCategory(categoryId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE));
    }

    private Optional<ProductCategory> findCategory(Long categoryId) {
        return jdbcClient.sql("""
                        SELECT id, parent_id, name, icon, sort_order, status, created_at, updated_at
                        FROM product_category
                        WHERE id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .query(this::mapCategory)
                .optional();
    }

    private Optional<ProductSpu> findSpu(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status, created_at, updated_at
                        FROM product_spu
                        WHERE id = :spuId
                        """)
                .param("spuId", spuId)
                .query(this::mapSpu)
                .optional();
    }

    private List<ProductSku> findSkusBySpuId(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               stock_available, weight_gram, image, status, sort_order, created_at, updated_at
                        FROM product_sku
                        WHERE spu_id = :spuId
                        ORDER BY sort_order ASC, id ASC
                        """)
                .param("spuId", spuId)
                .query(this::mapSku)
                .list();
    }

    private Optional<ProductSku> findSku(Long skuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               stock_available, weight_gram, image, status, sort_order, created_at, updated_at
                        FROM product_sku
                        WHERE id = :skuId
                        """)
                .param("skuId", skuId)
                .query(this::mapSku)
                .optional();
    }

    private CategoryStatus requireCategoryStatus(String status) {
        return parseEnum(status, CategoryStatus.class, ErrorCode.VALIDATION_FAILED);
    }

    private SkuStatus requireSkuStatus(String status) {
        return parseEnum(status, SkuStatus.class, ErrorCode.VALIDATION_FAILED);
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, ErrorCode errorCode) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(errorCode);
        }
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Failed to retrieve generated key");
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private ProductCategory mapCategory(ResultSet rs, int rowNum) throws SQLException {
        return new ProductCategory(
                rs.getLong("id"),
                rs.getLong("parent_id"),
                rs.getString("name"),
                rs.getString("icon"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private ProductSpu mapSpu(ResultSet rs, int rowNum) throws SQLException {
        return new ProductSpu(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getString("selling_points"),
                rs.getString("detail_html"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private ProductSku mapSku(ResultSet rs, int rowNum) throws SQLException {
        return new ProductSku(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getString("sku_code"),
                rs.getString("spec_json"),
                rs.getString("spec_text"),
                rs.getLong("price_cent"),
                rs.getLong("original_price_cent"),
                rs.getInt("stock_available"),
                rs.getInt("weight_gram"),
                rs.getString("image"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }
}
