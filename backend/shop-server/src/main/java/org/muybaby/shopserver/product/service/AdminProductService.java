package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.CategoryStatus;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminProductImageUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.entity.ProductCategory;
import org.muybaby.shopserver.product.entity.ProductSku;
import org.muybaby.shopserver.product.entity.ProductSpu;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AdminProductService {

    private static final String SYSTEM_OPERATOR_TYPE = "SYSTEM";
    private static final long SYSTEM_OPERATOR_ID = 0L;
    private static final String ADMIN_OPERATOR_TYPE = "ADMIN";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;

    public AdminProductService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
    }

    @Transactional
    public Long createCategory(AdminCategoryRequest request) {
        String status = requireCategoryStatus(request.status()).name();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_category (parent_id, name, icon, icon_file_id, sort_order, status)
                        VALUES (:parentId, :name, :icon, :iconFileId, :sortOrder, :status)
                        """,
                new MapSqlParameterSource()
                        .addValue("parentId", request.parentId())
                        .addValue("name", request.name())
                        .addValue("icon", defaultString(request.icon()))
                        .addValue("iconFileId", request.iconFileId())
                        .addValue("sortOrder", request.sortOrder())
                        .addValue("status", status),
                keyHolder,
                new String[]{"id"});
        Long categoryId = requireGeneratedId(keyHolder);
        syncCategoryFileUsages(categoryId, request);
        return categoryId;
    }

    @Transactional
    public void updateCategory(Long categoryId, AdminCategoryRequest request) {
        String status = requireCategoryStatus(request.status()).name();
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_category
                        SET parent_id = :parentId,
                            name = :name,
                            icon = :icon,
                            icon_file_id = :iconFileId,
                            sort_order = :sortOrder,
                            status = :status,
                            updated_at = :updatedAt
                        WHERE id = :categoryId
                        """)
                .param("parentId", request.parentId())
                .param("name", request.name())
                .param("icon", defaultString(request.icon()))
                .param("iconFileId", request.iconFileId())
                .param("sortOrder", request.sortOrder())
                .param("status", status)
                .param("updatedAt", LocalDateTime.now())
                .param("categoryId", categoryId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE);
        }
        syncCategoryFileUsages(categoryId, request);
    }

    @Transactional
    public Long createSpu(AdminSpuUpsertRequest request) {
        requireExistingCategory(request.categoryId());
        Long spuId = insertSpu(request);
        replaceImageRows(spuId, request.images());
        replaceSkuRows(spuId, request.skus(), Map.of(), SYSTEM_OPERATOR_TYPE, SYSTEM_OPERATOR_ID);
        syncSpuFileUsages(spuId, request);
        return spuId;
    }

    @Transactional
    public void updateSpu(Long spuId, AdminSpuUpsertRequest request) {
        updateSpu(spuId, request, SYSTEM_OPERATOR_TYPE, SYSTEM_OPERATOR_ID);
    }

    @Transactional
    public void updateSpu(Long spuId, AdminSpuUpsertRequest request, Long operatorId) {
        updateSpu(spuId, request, ADMIN_OPERATOR_TYPE, operatorId == null ? SYSTEM_OPERATOR_ID : operatorId);
    }

    private void updateSpu(Long spuId, AdminSpuUpsertRequest request, String operatorType, Long operatorId) {
        requireExistingCategory(request.categoryId());
        ProductSpu existingSpu = findSpu(spuId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        Map<Long, ProductSku> existingSkusById = new HashMap<>();
        for (ProductSku sku : findSkusBySpuId(spuId)) {
            existingSkusById.put(sku.id(), sku);
        }
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET category_id = :categoryId,
                            title = :title,
                            subtitle = :subtitle,
                            main_image = :mainImage,
                            main_image_file_id = :mainImageFileId,
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
                .param("mainImageFileId", request.mainImageFileId())
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
        replaceSkuRows(spuId, request.skus(), existingSkusById, operatorType, operatorId);
        syncSpuFileUsages(spuId, request);
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
        ProductSku sku = findSkuForUpdate(skuId).orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
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
                            category_id, title, subtitle, main_image, main_image_file_id, selling_points, detail_html, sort_order, status
                        )
                        VALUES (
                            :categoryId, :title, :subtitle, :mainImage, :mainImageFileId, :sellingPoints, :detailHtml, :sortOrder, :status
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("categoryId", request.categoryId())
                        .addValue("title", request.title())
                        .addValue("subtitle", defaultString(request.subtitle()))
                        .addValue("mainImage", request.mainImage())
                        .addValue("mainImageFileId", request.mainImageFileId())
                        .addValue("sellingPoints", defaultString(request.sellingPoints()))
                        .addValue("detailHtml", defaultString(request.detailHtml()))
                        .addValue("sortOrder", request.sortOrder())
                        .addValue("status", ProductStatus.DRAFT.name()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void replaceImageRows(Long spuId, List<AdminProductImageUpsertRequest> images) {
        jdbcClient.sql("DELETE FROM product_spu_image WHERE spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        List<AdminProductImageUpsertRequest> normalizedImages = images == null ? List.of() : images;
        for (int index = 0; index < normalizedImages.size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO product_spu_image (spu_id, url, file_id, sort_order)
                            VALUES (:spuId, :url, :fileId, :sortOrder)
                            """)
                    .param("spuId", spuId)
                    .param("url", normalizedImages.get(index).url())
                    .param("fileId", normalizedImages.get(index).fileId())
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private void replaceSkuRows(
            Long spuId,
            List<AdminSkuUpsertRequest> skus,
            Map<Long, ProductSku> existingSkusById,
            String operatorType,
            Long operatorId
    ) {
        jdbcClient.sql("DELETE FROM product_sku WHERE spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        List<AdminSkuUpsertRequest> normalizedSkus = skus == null ? List.of() : skus;
        Set<Long> retainedSkuIds = new HashSet<>();
        for (AdminSkuUpsertRequest sku : normalizedSkus) {
            Long skuId = insertSku(spuId, sku);
            retainedSkuIds.add(skuId);
            ProductSku existingSku = sku.id() == null ? null : existingSkusById.get(sku.id());
            if (existingSku == null) {
                insertStockLog(
                        skuId,
                        StockChangeType.INITIAL.name(),
                        0,
                        sku.stockAvailable(),
                        sku.stockAvailable(),
                        "initial stock",
                        operatorType,
                        operatorId
                );
            } else if (existingSku.stockAvailable() != sku.stockAvailable()) {
                int quantityDelta = sku.stockAvailable() - existingSku.stockAvailable();
                insertStockLog(
                        skuId,
                        StockChangeType.ADJUST.name(),
                        existingSku.stockAvailable(),
                        quantityDelta,
                        sku.stockAvailable(),
                        "spu update stock",
                        operatorType,
                        operatorId
                );
            }
            syncSkuFileUsages(skuId, sku);
        }
        for (Long existingSkuId : existingSkusById.keySet()) {
            if (!retainedSkuIds.contains(existingSkuId)) {
                storageUsageService.removeOwnerUsages(StorageUsageOwnerType.PRODUCT_SKU, existingSkuId);
            }
        }
    }

    private Long insertSku(Long spuId, AdminSkuUpsertRequest request) {
        String status = requireSkuStatus(request.status()).name();
        if (request.id() != null) {
            jdbcClient.sql("""
                            INSERT INTO product_sku (
                                id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                                stock_available, weight_gram, image, image_file_id, status, sort_order
                            )
                            VALUES (
                                :id, :spuId, :skuCode, :specJson, :specText, :priceCent, :originalPriceCent,
                                :stockAvailable, :weightGram, :image, :imageFileId, :status, :sortOrder
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
                    .param("imageFileId", request.imageFileId())
                    .param("status", status)
                    .param("sortOrder", request.sortOrder())
                    .update();
            return request.id();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_sku (
                            spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                            stock_available, weight_gram, image, image_file_id, status, sort_order
                        )
                        VALUES (
                            :spuId, :skuCode, :specJson, :specText, :priceCent, :originalPriceCent,
                            :stockAvailable, :weightGram, :image, :imageFileId, :status, :sortOrder
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
                        .addValue("imageFileId", request.imageFileId())
                        .addValue("status", status)
                        .addValue("sortOrder", request.sortOrder()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private void syncCategoryFileUsages(Long categoryId, AdminCategoryRequest request) {
        List<StorageUsageService.UsageAssignment> usages = request.iconFileId() == null
                ? List.of()
                : List.of(new StorageUsageService.UsageAssignment(
                request.iconFileId(),
                StorageFileUsageType.PRODUCT_CATEGORY_ICON,
                defaultString(request.icon()),
                1,
                false
        ));
        storageUsageService.replaceOwnerUsages(StorageUsageOwnerType.PRODUCT_CATEGORY, categoryId, request.name(), usages);
    }

    private void syncSpuFileUsages(Long spuId, AdminSpuUpsertRequest request) {
        Map<String, StorageUsageService.UsageAssignment> dedupedUsages = new LinkedHashMap<>();
        if (request.mainImageFileId() != null) {
            putUsage(dedupedUsages, request.mainImageFileId(), StorageFileUsageType.PRODUCT_SPU_MAIN, request.mainImage(), 1);
        }
        List<AdminProductImageUpsertRequest> gallery = request.images() == null ? List.of() : request.images();
        for (int index = 0; index < gallery.size(); index++) {
            AdminProductImageUpsertRequest image = gallery.get(index);
            if (image.fileId() != null) {
                putUsage(dedupedUsages, image.fileId(), StorageFileUsageType.PRODUCT_SPU_GALLERY, image.url(), index + 1);
            }
        }
        int detailSortOrder = 1000;
        for (ResolvedStorageFile detailFile : resolveDetailHtmlFiles(request.detailHtml())) {
            putUsage(dedupedUsages, detailFile.fileId(), StorageFileUsageType.PRODUCT_DETAIL_HTML, detailFile.publicUrl(), detailSortOrder++);
        }
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.PRODUCT_SPU,
                spuId,
                request.title(),
                List.copyOf(dedupedUsages.values())
        );
    }

    private void syncSkuFileUsages(Long skuId, AdminSkuUpsertRequest request) {
        List<StorageUsageService.UsageAssignment> usages = request.imageFileId() == null
                ? List.of()
                : List.of(new StorageUsageService.UsageAssignment(
                request.imageFileId(),
                StorageFileUsageType.PRODUCT_SKU_IMAGE,
                defaultString(request.image()),
                1,
                false
        ));
        storageUsageService.replaceOwnerUsages(StorageUsageOwnerType.PRODUCT_SKU, skuId, request.skuCode(), usages);
    }

    private void putUsage(
            Map<String, StorageUsageService.UsageAssignment> dedupedUsages,
            Long fileId,
            StorageFileUsageType usageType,
            String snapshotUrl,
            int sortOrder
    ) {
        if (fileId == null) {
            return;
        }
        dedupedUsages.putIfAbsent(
                usageType.name() + ":" + fileId,
                new StorageUsageService.UsageAssignment(fileId, usageType, defaultString(snapshotUrl), sortOrder, false)
        );
    }

    private List<ResolvedStorageFile> resolveDetailHtmlFiles(String detailHtml) {
        if (!StringUtils.hasText(detailHtml)) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select id, public_url
                        from storage_file
                        where status = 'ACTIVE'
                          and visibility = 'PUBLIC'
                          and public_url is not null
                          and public_url <> ''
                          and locate(public_url, :detailHtml) > 0
                        order by locate(public_url, :detailHtml), id
                        """)
                .param("detailHtml", detailHtml)
                .query((rs, rowNum) -> new ResolvedStorageFile(
                        rs.getLong("id"),
                        rs.getString("public_url")
                ))
                .list();
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
                        SELECT id, parent_id, name, icon, icon_file_id, sort_order, status, created_at, updated_at
                        FROM product_category
                        WHERE id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .query(this::mapCategory)
                .optional();
    }

    private Optional<ProductSpu> findSpu(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, category_id, title, subtitle, main_image, main_image_file_id, selling_points, detail_html, sort_order, status, created_at, updated_at
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
                               stock_available, weight_gram, image, image_file_id, status, sort_order, created_at, updated_at
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
                               stock_available, weight_gram, image, image_file_id, status, sort_order, created_at, updated_at
                        FROM product_sku
                        WHERE id = :skuId
                        """)
                .param("skuId", skuId)
                .query(this::mapSku)
                .optional();
    }

    private Optional<ProductSku> findSkuForUpdate(Long skuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               stock_available, weight_gram, image, image_file_id, status, sort_order, created_at, updated_at
                        FROM product_sku
                        WHERE id = :skuId
                        FOR UPDATE
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
                rs.getObject("icon_file_id", Long.class),
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
                rs.getObject("main_image_file_id", Long.class),
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
                rs.getObject("image_file_id", Long.class),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private record ResolvedStorageFile(Long fileId, String publicUrl) {
    }
}
