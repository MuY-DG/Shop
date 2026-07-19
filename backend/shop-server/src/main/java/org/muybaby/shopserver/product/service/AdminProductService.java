package org.muybaby.shopserver.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.product.CategoryStatus;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.ProductSpecType;
import org.muybaby.shopserver.product.ProductTag;
import org.muybaby.shopserver.product.SkuStatus;
import org.muybaby.shopserver.product.StockChangeType;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminProductImageUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuSpecGroupUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuSpecValueUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.entity.ProductCategory;
import org.muybaby.shopserver.product.entity.ProductSku;
import org.muybaby.shopserver.product.entity.ProductSpu;
import org.muybaby.shopserver.product.entity.ProductSpuImage;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.context.ApplicationEventPublisher;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AdminProductService {

    private static final String SYSTEM_OPERATOR_TYPE = "SYSTEM";
    private static final long SYSTEM_OPERATOR_ID = 0L;
    private static final String ADMIN_OPERATOR_TYPE = "ADMIN";
    private static final Pattern SPEC_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_SKU_COMBINATIONS = 100;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public AdminProductService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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
        ProductCategory existingCategory = findCategory(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE));
        AdminCategoryRequest normalizedRequest = normalizeCategoryRequest(request, existingCategory);
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
                .param("parentId", normalizedRequest.parentId())
                .param("name", normalizedRequest.name())
                .param("icon", defaultString(normalizedRequest.icon()))
                .param("iconFileId", normalizedRequest.iconFileId())
                .param("sortOrder", normalizedRequest.sortOrder())
                .param("status", status)
                .param("updatedAt", LocalDateTime.now())
                .param("categoryId", categoryId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE);
        }
        syncCategoryFileUsages(categoryId, normalizedRequest);
        publishHomeChanged();
    }

    @Transactional
    public Long createSpu(AdminSpuUpsertRequest request) {
        return createSpu(request, true);
    }

    @Transactional
    public Long createSpu(AdminSpuUpsertRequest request, boolean stockWriteAllowed) {
        AdminSpuUpsertRequest normalizedRequest = normalizeCreateSpuRequest(request);
        requireExistingCategory(normalizedRequest.categoryId());
        requireFreightTemplate(normalizedRequest.freightTemplateId(), false);
        validateProductAggregate(normalizedRequest, false);
        requireStockWritePermission(normalizedRequest, Map.of(), stockWriteAllowed);
        Long spuId = insertSpu(normalizedRequest);
        lockRequestedGuaranteeServices(normalizedRequest);
        replaceImageRows(spuId, normalizedRequest.images());
        Map<String, PersistedSpecValue> specValuesByKey = replaceSpecRows(spuId, normalizedRequest);
        upsertSkuRows(spuId, normalizedRequest, Map.of(), specValuesByKey, SYSTEM_OPERATOR_TYPE, SYSTEM_OPERATOR_ID);
        replaceProductAssociations(spuId, normalizedRequest);
        syncSpuFileUsages(spuId, normalizedRequest);
        publishHomeChanged();
        return spuId;
    }

    @Transactional
    public void updateSpu(Long spuId, AdminSpuUpsertRequest request) {
        updateSpu(spuId, request, SYSTEM_OPERATOR_TYPE, SYSTEM_OPERATOR_ID, true);
    }

    @Transactional
    public void updateSpu(Long spuId, AdminSpuUpsertRequest request, Long operatorId) {
        updateSpu(spuId, request, ADMIN_OPERATOR_TYPE, operatorId == null ? SYSTEM_OPERATOR_ID : operatorId, true);
    }

    @Transactional
    public void updateSpu(
            Long spuId,
            AdminSpuUpsertRequest request,
            Long operatorId,
            boolean stockWriteAllowed
    ) {
        updateSpu(
                spuId,
                request,
                ADMIN_OPERATOR_TYPE,
                operatorId == null ? SYSTEM_OPERATOR_ID : operatorId,
                stockWriteAllowed
        );
    }

    private void updateSpu(
            Long spuId,
            AdminSpuUpsertRequest request,
            String operatorType,
            Long operatorId,
            boolean stockWriteAllowed
    ) {
        ProductSpu existingSpu = findSpuForUpdate(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        List<ProductSpuImage> existingImages = findSpuImagesBySpuId(spuId);
        Map<Long, ProductSku> existingSkusById = new HashMap<>();
        for (ProductSku sku : findSkusBySpuIdForUpdate(spuId)) {
            existingSkusById.put(sku.id(), sku);
        }
        AdminSpuUpsertRequest normalizedRequest = normalizeSpuRequest(request, existingSpu, existingImages, existingSkusById);
        boolean remainsOnSale = ProductStatus.ON_SALE.name().equals(existingSpu.status());
        ProductCategory category = requireExistingCategory(normalizedRequest.categoryId());
        if (remainsOnSale && !CategoryStatus.ENABLED.name().equals(category.status())) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        requireFreightTemplate(normalizedRequest.freightTemplateId(), remainsOnSale);
        validateProductAggregate(normalizedRequest, remainsOnSale);
        requireStockWritePermission(normalizedRequest, existingSkusById, stockWriteAllowed);
        lockRequestedGuaranteeServices(normalizedRequest);
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET category_id = :categoryId,
                            title = :title,
                            subtitle = :subtitle,
                            main_image = :mainImage,
                            main_image_file_id = :mainImageFileId,
                            main_video = :mainVideo,
                            main_video_file_id = :mainVideoFileId,
                            spec_type = :specType,
                            freight_template_id = :freightTemplateId,
                            virtual_sales = :virtualSales,
                            selling_points = :sellingPoints,
                            detail_html = :detailHtml,
                            sort_order = :sortOrder,
                            status = :status,
                            updated_at = :updatedAt
                        WHERE id = :spuId AND deleted_at IS NULL
                        """)
                .param("categoryId", normalizedRequest.categoryId())
                .param("title", normalizedRequest.title())
                .param("subtitle", defaultString(normalizedRequest.subtitle()))
                .param("mainImage", normalizedRequest.mainImage())
                .param("mainImageFileId", normalizedRequest.mainImageFileId())
                .param("mainVideo", defaultString(normalizedRequest.mainVideo()))
                .param("mainVideoFileId", normalizedRequest.mainVideoFileId())
                .param("specType", normalizedRequest.specType())
                .param("freightTemplateId", normalizedRequest.freightTemplateId())
                .param("virtualSales", normalizedRequest.virtualSales())
                .param("sellingPoints", defaultString(normalizedRequest.sellingPoints()))
                .param("detailHtml", defaultString(normalizedRequest.detailHtml()))
                .param("sortOrder", normalizedRequest.sortOrder())
                .param("status", existingSpu.status())
                .param("updatedAt", LocalDateTime.now())
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        publishHomeChanged();
        replaceImageRows(spuId, normalizedRequest.images());
        Map<String, PersistedSpecValue> specValuesByKey = normalizedRequest.specGroupsSpecified()
                ? replaceSpecRows(spuId, normalizedRequest)
                : findPersistedSpecValuesByKey(spuId);
        upsertSkuRows(spuId, normalizedRequest, existingSkusById, specValuesByKey, operatorType, operatorId);
        replaceProductAssociations(spuId, normalizedRequest);
        syncSpuFileUsages(spuId, normalizedRequest);
    }

    @Transactional
    public void publishSpu(Long spuId) {
        ProductSpu spu = findSpuForUpdate(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        ProductCategory category = findCategory(spu.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        requireFreightTemplate(spu.freightTemplateId(), true);
        List<ProductSku> activeSkus = findSkusBySpuId(spuId).stream()
                .filter(sku -> sku.deletedAt() == null)
                .toList();
        List<ProductSku> enabledSkus = activeSkus.stream()
                .filter(sku -> SkuStatus.ENABLED.name().equals(sku.status()) && sku.priceCent() != null && sku.priceCent() > 0)
                .toList();
        long defaultSkuCount = enabledSkus.stream().filter(sku -> Boolean.TRUE.equals(sku.defaultSelected())).count();
        boolean specValid = ProductSpecType.SINGLE.name().equals(spu.specType())
                ? activeSkus.size() == 1
                : activeSkus.size() <= MAX_SKU_COMBINATIONS && hasValidMultiSpecShape(spuId);
        if (!CategoryStatus.ENABLED.name().equals(category.status())
                || !StringUtils.hasText(spu.title())
                || !StringUtils.hasText(spu.mainImage())
                || enabledSkus.isEmpty()
                || defaultSkuCount != 1
                || !specValid) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET status = :status, updated_at = :updatedAt
                        WHERE id = :spuId AND deleted_at IS NULL
                        """)
                .param("status", ProductStatus.ON_SALE.name())
                .param("updatedAt", LocalDateTime.now())
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        publishHomeChanged();
    }

    @Transactional
    public void unpublishSpu(Long spuId) {
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET status = :status, updated_at = :updatedAt
                        WHERE id = :spuId AND deleted_at IS NULL
                        """)
                .param("status", ProductStatus.OFF_SALE.name())
                .param("updatedAt", LocalDateTime.now())
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        publishHomeChanged();
    }

    @Transactional
    public void deleteSpu(Long spuId) {
        findSpuForUpdate(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        LocalDateTime deletedAt = LocalDateTime.now();
        int updatedRows = jdbcClient.sql("""
                        UPDATE product_spu
                        SET status = :status,
                            deleted_at = :deletedAt,
                            updated_at = :deletedAt
                        WHERE id = :spuId
                          AND deleted_at IS NULL
                          AND purged_at IS NULL
                        """)
                .param("status", ProductStatus.OFF_SALE.name())
                .param("deletedAt", deletedAt)
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        publishHomeChanged();
    }

    @Transactional
    public void restoreSpu(Long spuId) {
        ProductSpu recycledSpu = findRecycledSpuForUpdate(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_IN_RECYCLE_BIN));
        findSkusBySpuIdForUpdate(spuId);
        LocalDateTime restoredAt = LocalDateTime.now();

        restoreLegacyDeletedChildren(spuId, recycledSpu.deletedAt(), restoredAt);
        int updatedRows = jdbcClient.sql("""
                        update product_spu
                        set status = :status,
                            deleted_at = null,
                            updated_at = :updatedAt
                        where id = :spuId
                          and deleted_at is not null
                          and purged_at is null
                        """)
                .param("status", ProductStatus.OFF_SALE.name())
                .param("updatedAt", restoredAt)
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_IN_RECYCLE_BIN);
        }
        restoreProductFileUsages(recycledSpu);
        publishHomeChanged();
    }

    @Transactional
    public void purgeSpu(Long spuId, String confirmationTitle) {
        ProductSpu recycledSpu = findRecycledSpuForUpdate(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_IN_RECYCLE_BIN));
        if (!Objects.equals(recycledSpu.title(), confirmationTitle)) {
            throw new BusinessException(ErrorCode.PRODUCT_PURGE_CONFIRMATION_MISMATCH);
        }

        List<ProductSku> skus = findSkusBySpuIdForUpdate(spuId);
        requireNoLockedStockForPurge(spuId);
        List<ProductBannerReference> productBanners = lockProductBannersForPurge(spuId);
        requireNoEnabledProductBannerForPurge(productBanners);
        List<HomeProductReference> homeProducts = lockHomeProductsForPurge(spuId);
        requireNoEnabledHomeProductForPurge(homeProducts);
        LocalDateTime purgedAt = LocalDateTime.now();

        detachDisabledProductBanners(productBanners, purgedAt);
        deleteDisabledHomeProducts(homeProducts);
        deleteCartItems(skus);
        deleteProductOwnedFileUsages(spuId, skus);
        deleteProductPrivateRows(spuId, skus);
        tombstoneSkus(skus, purgedAt);

        int updatedRows = jdbcClient.sql("""
                        update product_spu
                        set title = :title,
                            subtitle = '',
                            main_image = '',
                            main_image_file_id = null,
                            main_video = '',
                            main_video_file_id = null,
                            spec_type = :specType,
                            virtual_sales = 0,
                            selling_points = '',
                            detail_html = '',
                            sort_order = 0,
                            status = :status,
                            purged_at = :purgedAt,
                            updated_at = :purgedAt
                        where id = :spuId
                          and deleted_at is not null
                          and purged_at is null
                        """)
                .param("title", purgedProductTitle(spuId))
                .param("specType", ProductSpecType.SINGLE.name())
                .param("status", ProductStatus.OFF_SALE.name())
                .param("purgedAt", purgedAt)
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_IN_RECYCLE_BIN);
        }
        publishHomeChanged();
    }

    private void restoreLegacyDeletedChildren(Long spuId, LocalDateTime productDeletedAt, LocalDateTime restoredAt) {
        if (productDeletedAt == null) {
            return;
        }
        jdbcClient.sql("""
                        update product_sku
                        set deleted_at = null,
                            updated_at = :restoredAt
                        where spu_id = :spuId
                          and deleted_at = :productDeletedAt
                        """)
                .param("restoredAt", restoredAt)
                .param("spuId", spuId)
                .param("productDeletedAt", productDeletedAt)
                .update();

        List<Long> legacyDeletedGroupIds = jdbcClient.sql("""
                        select id
                        from product_spu_spec_group
                        where spu_id = :spuId
                          and deleted_at >= :productDeletedAt
                        order by id
                        for update
                        """)
                .param("spuId", spuId)
                .param("productDeletedAt", productDeletedAt)
                .query(Long.class)
                .list();
        for (Long groupId : legacyDeletedGroupIds) {
            jdbcClient.sql("""
                            update product_spu_spec_value
                            set deleted_at = null,
                                updated_at = :restoredAt
                            where group_id = :groupId
                              and deleted_at >= :productDeletedAt
                            """)
                    .param("restoredAt", restoredAt)
                    .param("groupId", groupId)
                    .param("productDeletedAt", productDeletedAt)
                    .update();
            jdbcClient.sql("""
                            update product_spu_spec_group
                            set deleted_at = null,
                                updated_at = :restoredAt
                            where id = :groupId
                              and deleted_at >= :productDeletedAt
                            """)
                    .param("restoredAt", restoredAt)
                    .param("groupId", groupId)
                    .param("productDeletedAt", productDeletedAt)
                    .update();
        }
    }

    private void restoreProductFileUsages(ProductSpu spu) {
        restoreOwnerUsage(
                StorageUsageOwnerType.PRODUCT_SPU,
                spu.id(),
                spu.title(),
                spu.mainImageFileId(),
                StorageFileUsageType.PRODUCT_SPU_MAIN,
                spu.mainImage(),
                1
        );
        restoreOwnerUsage(
                StorageUsageOwnerType.PRODUCT_SPU,
                spu.id(),
                spu.title(),
                spu.mainVideoFileId(),
                StorageFileUsageType.PRODUCT_SPU_VIDEO,
                spu.mainVideo(),
                2
        );
        List<ProductSpuImage> gallery = findSpuImagesBySpuId(spu.id());
        for (int index = 0; index < gallery.size(); index++) {
            ProductSpuImage image = gallery.get(index);
            restoreOwnerUsage(
                    StorageUsageOwnerType.PRODUCT_SPU,
                    spu.id(),
                    spu.title(),
                    image.fileId(),
                    StorageFileUsageType.PRODUCT_SPU_GALLERY,
                    image.url(),
                    index + 1
            );
        }
        int detailSortOrder = 1000;
        for (ResolvedStorageFile detailFile : resolveDetailHtmlFiles(spu.detailHtml())) {
            restoreOwnerUsage(
                    StorageUsageOwnerType.PRODUCT_SPU,
                    spu.id(),
                    spu.title(),
                    detailFile.fileId(),
                    StorageFileUsageType.PRODUCT_DETAIL_HTML,
                    detailFile.publicUrl(),
                    detailSortOrder++
            );
        }

        for (ProductSku sku : findSkusBySpuId(spu.id())) {
            if (sku.deletedAt() == null) {
                restoreOwnerUsage(
                        StorageUsageOwnerType.PRODUCT_SKU,
                        sku.id(),
                        sku.skuCode(),
                        sku.imageFileId(),
                        StorageFileUsageType.PRODUCT_SKU_IMAGE,
                        sku.image(),
                        1
                );
            }
        }
        List<RestorableSpecValue> specValues = jdbcClient.sql("""
                        select v.id, v.value_name, v.image, v.image_file_id
                        from product_spu_spec_value v
                        join product_spu_spec_group g on g.id = v.group_id
                        where g.spu_id = :spuId
                          and g.deleted_at is null
                          and v.deleted_at is null
                        order by g.sort_order, g.id, v.sort_order, v.id
                        """)
                .param("spuId", spu.id())
                .query((rs, rowNum) -> new RestorableSpecValue(
                        rs.getLong("id"),
                        rs.getString("value_name"),
                        rs.getString("image"),
                        rs.getObject("image_file_id", Long.class)
                ))
                .list();
        for (RestorableSpecValue specValue : specValues) {
            restoreOwnerUsage(
                    StorageUsageOwnerType.PRODUCT_SPEC_VALUE,
                    specValue.id(),
                    specValue.name(),
                    specValue.imageFileId(),
                    StorageFileUsageType.PRODUCT_SPEC_VALUE_IMAGE,
                    specValue.image(),
                    1
            );
        }
    }

    private void restoreOwnerUsage(
            StorageUsageOwnerType ownerType,
            Long ownerId,
            String ownerLabel,
            Long fileId,
            StorageFileUsageType usageType,
            String snapshotUrl,
            int sortOrder
    ) {
        if (fileId == null) {
            return;
        }
        storageUsageService.restoreOwnerUsageIfAvailable(
                ownerType,
                ownerId,
                ownerLabel,
                new StorageUsageService.UsageAssignment(
                        fileId,
                        usageType,
                        defaultString(snapshotUrl),
                        sortOrder,
                        false
                )
        );
    }

    private void requireNoLockedStockForPurge(Long spuId) {
        Long lockedStockCount = jdbcClient.sql("""
                        select count(*)
                        from stock_lock l
                        join product_sku k on k.id = l.sku_id
                        where k.spu_id = :spuId
                          and l.status = 'LOCKED'
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        if (lockedStockCount != null && lockedStockCount > 0) {
            throw new BusinessException(ErrorCode.PRODUCT_PURGE_HAS_LOCKED_STOCK);
        }
    }

    private List<ProductBannerReference> lockProductBannersForPurge(Long spuId) {
        return jdbcClient.sql("""
                        select id, status
                        from home_banner
                        where jump_type = 'PRODUCT'
                          and jump_target_id = :spuId
                        order by id
                        for update
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new ProductBannerReference(
                        rs.getLong("id"),
                        rs.getString("status")
                ))
                .list();
    }

    private void requireNoEnabledProductBannerForPurge(List<ProductBannerReference> banners) {
        if (banners.stream().anyMatch(banner -> "ENABLED".equals(banner.status()))) {
            throw new BusinessException(ErrorCode.PRODUCT_PURGE_HAS_ACTIVE_BANNER);
        }
    }

    private void detachDisabledProductBanners(List<ProductBannerReference> banners, LocalDateTime updatedAt) {
        for (ProductBannerReference banner : banners) {
            jdbcClient.sql("""
                            update home_banner
                            set jump_type = 'NONE',
                                jump_target_id = null,
                                jump_path = '',
                                updated_at = :updatedAt
                            where id = :bannerId
                              and jump_type = 'PRODUCT'
                              and status <> 'ENABLED'
                            """)
                    .param("updatedAt", updatedAt)
                    .param("bannerId", banner.id())
                    .update();
        }
    }

    private List<HomeProductReference> lockHomeProductsForPurge(Long spuId) {
        return jdbcClient.sql("""
                        select id, status
                        from home_product_item
                        where spu_id = :spuId
                        order by id
                        for update
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new HomeProductReference(
                        rs.getLong("id"),
                        rs.getString("status")
                ))
                .list();
    }

    private void requireNoEnabledHomeProductForPurge(List<HomeProductReference> homeProducts) {
        if (homeProducts.stream().anyMatch(item -> "ENABLED".equals(item.status()))) {
            throw new BusinessException(ErrorCode.PRODUCT_PURGE_HAS_ACTIVE_HOME_ITEM);
        }
    }

    private void deleteDisabledHomeProducts(List<HomeProductReference> homeProducts) {
        for (HomeProductReference item : homeProducts) {
            storageUsageService.removeOwnerUsages(StorageUsageOwnerType.HOME_PRODUCT_ITEM, item.id());
            jdbcClient.sql("""
                            delete from home_product_item
                            where id = :itemId
                              and status <> 'ENABLED'
                            """)
                    .param("itemId", item.id())
                    .update();
        }
    }

    private void publishHomeChanged() {
        eventPublisher.publishEvent(PublicContentChangedEvent.home());
    }

    private void deleteCartItems(List<ProductSku> skus) {
        for (ProductSku sku : skus) {
            jdbcClient.sql("delete from cart_item where sku_id = :skuId")
                    .param("skuId", sku.id())
                    .update();
        }
    }

    private void deleteProductOwnedFileUsages(Long spuId, List<ProductSku> skus) {
        jdbcClient.sql("""
                        delete from storage_asset_usage
                        where owner_type = :ownerType
                          and owner_id = :spuId
                        """)
                .param("ownerType", StorageUsageOwnerType.PRODUCT_SPU.name())
                .param("spuId", spuId)
                .update();
        for (ProductSku sku : skus) {
            jdbcClient.sql("""
                            delete from storage_asset_usage
                            where owner_type = :ownerType
                              and owner_id = :skuId
                            """)
                    .param("ownerType", StorageUsageOwnerType.PRODUCT_SKU.name())
                    .param("skuId", sku.id())
                    .update();
        }
        List<Long> specValueIds = jdbcClient.sql("""
                        select v.id
                        from product_spu_spec_value v
                        join product_spu_spec_group g on g.id = v.group_id
                        where g.spu_id = :spuId
                        order by v.id
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list();
        for (Long specValueId : specValueIds) {
            jdbcClient.sql("""
                            delete from storage_asset_usage
                            where owner_type = :ownerType
                              and owner_id = :specValueId
                            """)
                    .param("ownerType", StorageUsageOwnerType.PRODUCT_SPEC_VALUE.name())
                    .param("specValueId", specValueId)
                    .update();
        }
    }

    private void deleteProductPrivateRows(Long spuId, List<ProductSku> skus) {
        for (ProductSku sku : skus) {
            jdbcClient.sql("delete from product_sku_spec_value where sku_id = :skuId")
                    .param("skuId", sku.id())
                    .update();
        }
        List<Long> groupIds = jdbcClient.sql("""
                        select id from product_spu_spec_group
                        where spu_id = :spuId
                        order by id
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list();
        for (Long groupId : groupIds) {
            jdbcClient.sql("delete from product_spu_spec_value where group_id = :groupId")
                    .param("groupId", groupId)
                    .update();
        }
        jdbcClient.sql("delete from product_spu_spec_group where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("delete from product_spu_image where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("delete from product_spu_tag where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("delete from product_spu_guarantee_service where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("delete from product_spu_coupon where spu_id = :spuId")
                .param("spuId", spuId)
                .update();
    }

    private void tombstoneSkus(List<ProductSku> skus, LocalDateTime purgedAt) {
        for (ProductSku sku : skus) {
            jdbcClient.sql("""
                            update product_sku
                            set spec_json = '{}',
                                spec_text = :specText,
                                price_cent = 0,
                                original_price_cent = 0,
                                cost_price_cent = null,
                                stock_available = 0,
                                weight_gram = null,
                                volume_cubic_meter = null,
                                image = '',
                                image_file_id = null,
                                status = :status,
                                is_default = false,
                                combination_key = :combinationKey,
                                sort_order = 0,
                                deleted_at = coalesce(deleted_at, :purgedAt),
                                updated_at = :purgedAt
                            where id = :skuId
                              and spu_id = :spuId
                            """)
                    .param("specText", "[已永久删除规格 #" + sku.id() + "]")
                    .param("status", SkuStatus.DISABLED.name())
                    .param("combinationKey", "PURGED:" + sku.id())
                    .param("purgedAt", purgedAt)
                    .param("skuId", sku.id())
                    .param("spuId", sku.spuId())
                    .update();
        }
    }

    private String purgedProductTitle(Long spuId) {
        return "[已永久删除商品 #" + spuId + "]";
    }

    @Transactional
    public void adjustSkuStock(Long skuId, AdminStockAdjustmentRequest request, Long operatorId) {
        Long spuId = findSkuSpuId(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
        findSpuForUpdate(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SKU_UNAVAILABLE));
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

    @Transactional
    public void updateSkuLowStockThreshold(Long skuId, Integer lowStockThreshold) {
        if (lowStockThreshold == null || lowStockThreshold < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int updatedRows = jdbcClient.sql("""
                        update product_sku
                        set low_stock_threshold = :lowStockThreshold,
                            updated_at = :updatedAt
                        where id = :skuId and deleted_at is null
                        """)
                .param("lowStockThreshold", lowStockThreshold)
                .param("updatedAt", LocalDateTime.now())
                .param("skuId", skuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
    }

    private Long insertSpu(AdminSpuUpsertRequest request) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_spu (
                            category_id, title, subtitle, main_image, main_image_file_id,
                            main_video, main_video_file_id, spec_type, freight_template_id, virtual_sales,
                            selling_points, detail_html, sort_order, status
                        )
                        VALUES (
                            :categoryId, :title, :subtitle, :mainImage, :mainImageFileId,
                            :mainVideo, :mainVideoFileId, :specType, :freightTemplateId, :virtualSales,
                            :sellingPoints, :detailHtml, :sortOrder, :status
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("categoryId", request.categoryId())
                        .addValue("title", request.title())
                        .addValue("subtitle", defaultString(request.subtitle()))
                        .addValue("mainImage", request.mainImage())
                        .addValue("mainImageFileId", request.mainImageFileId())
                        .addValue("mainVideo", defaultString(request.mainVideo()))
                        .addValue("mainVideoFileId", request.mainVideoFileId())
                        .addValue("specType", request.specType())
                        .addValue("freightTemplateId", request.freightTemplateId())
                        .addValue("virtualSales", request.virtualSales())
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

    private AdminCategoryRequest normalizeCategoryRequest(AdminCategoryRequest request, ProductCategory existingCategory) {
        Long iconFileId = request.iconFileId();
        if (!request.iconFileIdSpecified() && iconFileId == null && sameUrlSnapshot(request.icon(), existingCategory.icon())) {
            iconFileId = existingCategory.iconFileId();
        }
        return new AdminCategoryRequest(
                request.parentId(),
                request.name(),
                request.icon(),
                iconFileId,
                request.sortOrder(),
                request.status(),
                request.iconFileIdSpecified()
        );
    }

    private AdminSpuUpsertRequest normalizeCreateSpuRequest(AdminSpuUpsertRequest request) {
        List<AdminSkuUpsertRequest> normalizedSkus = normalizeSkuRequests(request.skus(), Map.of(), true);
        String specType = StringUtils.hasText(request.specType())
                ? requireSpecType(request.specType()).name()
                : (normalizedSkus.size() > 1 ? ProductSpecType.MULTI.name() : ProductSpecType.SINGLE.name());
        return new AdminSpuUpsertRequest(
                request.categoryId(),
                request.title(),
                request.subtitle(),
                request.mainImage(),
                request.mainImageFileId(),
                defaultString(request.mainVideo()),
                request.mainVideoFileId(),
                specType,
                request.freightTemplateId() == null ? 1L : request.freightTemplateId(),
                request.virtualSales() == null ? 0L : request.virtualSales(),
                request.sellingPoints(),
                request.detailHtml(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.images() == null ? List.of() : request.images(),
                normalizedSkus,
                request.specGroups(),
                request.tags(),
                request.guaranteeServiceIds(),
                request.mainImageFileIdSpecified(),
                request.mainVideoFileIdSpecified(),
                request.specTypeSpecified(),
                request.specGroupsSpecified(),
                request.tagsSpecified(),
                request.guaranteeServiceIdsSpecified()
        );
    }

    private AdminSpuUpsertRequest normalizeSpuRequest(
            AdminSpuUpsertRequest request,
            ProductSpu existingSpu,
            List<ProductSpuImage> existingImages,
            Map<Long, ProductSku> existingSkusById
    ) {
        Long mainImageFileId = request.mainImageFileId();
        if (!request.mainImageFileIdSpecified()
                && mainImageFileId == null
                && sameUrlSnapshot(request.mainImage(), existingSpu.mainImage())) {
            mainImageFileId = existingSpu.mainImageFileId();
        }
        String mainVideo = request.mainVideo() == null ? existingSpu.mainVideo() : request.mainVideo();
        Long mainVideoFileId = request.mainVideoFileId();
        if (!request.mainVideoFileIdSpecified()
                && mainVideoFileId == null
                && sameUrlSnapshot(mainVideo, existingSpu.mainVideo())) {
            mainVideoFileId = existingSpu.mainVideoFileId();
        }

        Map<String, Deque<Long>> existingGalleryFileIdsByUrl = new HashMap<>();
        for (ProductSpuImage image : existingImages) {
            if (image.fileId() != null) {
                existingGalleryFileIdsByUrl
                        .computeIfAbsent(defaultString(image.url()), key -> new ArrayDeque<>())
                        .addLast(image.fileId());
            }
        }

        List<AdminProductImageUpsertRequest> normalizedImages = request.images() == null
                ? existingImages.stream()
                .map(image -> new AdminProductImageUpsertRequest(image.url(), image.fileId(), false))
                .toList()
                : request.images().stream()
                .map(image -> {
                    Long fileId = image.fileId();
                    if (!image.fileIdSpecified() && fileId == null) {
                        Deque<Long> existingFileIds = existingGalleryFileIdsByUrl.get(defaultString(image.url()));
                        if (existingFileIds != null && !existingFileIds.isEmpty()) {
                            fileId = existingFileIds.removeFirst();
                        }
                    }
                    return new AdminProductImageUpsertRequest(image.url(), fileId, image.fileIdSpecified());
                })
                .toList();

        boolean preserveOmittedSpecValueKeys = !(request.specGroupsSpecified() && request.specGroups().isEmpty());
        List<AdminSkuUpsertRequest> normalizedSkus = request.skus() == null
                ? existingSkusById.values().stream()
                .filter(sku -> sku.deletedAt() == null)
                .sorted((left, right) -> {
                    int sortComparison = Integer.compare(left.sortOrder(), right.sortOrder());
                    return sortComparison != 0 ? sortComparison : Long.compare(left.id(), right.id());
                })
                .map(sku -> existingSkuRequest(sku, preserveOmittedSpecValueKeys))
                .toList()
                : normalizeSkuRequests(request.skus(), existingSkusById, preserveOmittedSpecValueKeys);
        List<AdminSpuSpecGroupUpsertRequest> normalizedSpecGroups = request.specGroupsSpecified()
                ? request.specGroups()
                : findActiveSpecGroupRequests(existingSpu.id());
        List<String> normalizedTags = request.tagsSpecified()
                ? request.tags()
                : findProductTags(existingSpu.id());
        List<Long> normalizedGuaranteeServiceIds = request.guaranteeServiceIdsSpecified()
                ? request.guaranteeServiceIds()
                : findGuaranteeServiceIds(existingSpu.id());

        return new AdminSpuUpsertRequest(
                request.categoryId(),
                request.title(),
                request.subtitle() == null ? existingSpu.subtitle() : request.subtitle(),
                request.mainImage(),
                mainImageFileId,
                mainVideo,
                mainVideoFileId,
                StringUtils.hasText(request.specType())
                        ? requireSpecType(request.specType()).name()
                        : (request.skus() != null && normalizedSkus.size() > 1
                        ? ProductSpecType.MULTI.name()
                        : existingSpu.specType()),
                request.freightTemplateId() == null ? existingSpu.freightTemplateId() : request.freightTemplateId(),
                request.virtualSales() == null ? existingSpu.virtualSales() : request.virtualSales(),
                request.sellingPoints() == null ? existingSpu.sellingPoints() : request.sellingPoints(),
                request.detailHtml() == null ? existingSpu.detailHtml() : request.detailHtml(),
                request.sortOrder() == null ? existingSpu.sortOrder() : request.sortOrder(),
                normalizedImages,
                normalizedSkus,
                normalizedSpecGroups,
                normalizedTags,
                normalizedGuaranteeServiceIds,
                request.mainImageFileIdSpecified(),
                request.mainVideoFileIdSpecified(),
                request.specTypeSpecified(),
                request.specGroupsSpecified(),
                request.tagsSpecified(),
                request.guaranteeServiceIdsSpecified()
        );
    }

    private List<AdminSkuUpsertRequest> normalizeSkuRequests(
            List<AdminSkuUpsertRequest> requests,
            Map<Long, ProductSku> existingSkusById,
            boolean preserveOmittedSpecValueKeys
    ) {
        if (requests == null) {
            return List.of();
        }
        Map<String, ProductSku> existingSkusByCode = new HashMap<>();
        for (ProductSku existingSku : existingSkusById.values()) {
            existingSkusByCode.put(existingSku.skuCode(), existingSku);
        }
        List<AdminSkuUpsertRequest> normalized = new java.util.ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            AdminSkuUpsertRequest request = requests.get(index);
            ProductSku existingSku = request.id() == null
                    ? existingSkusByCode.get(request.skuCode())
                    : existingSkusById.get(request.id());
            normalized.add(normalizeSkuRequest(request, existingSku, index, preserveOmittedSpecValueKeys));
        }
        normalizeDefaultSku(normalized);
        return List.copyOf(normalized);
    }

    private AdminSkuUpsertRequest normalizeSkuRequest(
            AdminSkuUpsertRequest request,
            ProductSku existingSku,
            int index,
            boolean preserveOmittedSpecValueKeys
    ) {
        Long imageFileId = request.imageFileId();
        if (!request.imageFileIdSpecified()
                && imageFileId == null
                && existingSku != null
                && sameUrlSnapshot(request.image(), existingSku.image())) {
            imageFileId = existingSku.imageFileId();
        }
        List<String> specValueKeys = request.specValueKeys();
        if (preserveOmittedSpecValueKeys && !request.specValueKeysSpecified() && existingSku != null) {
            specValueKeys = findSkuSpecValueKeys(existingSku.id());
        }
        Long costPriceCent = !request.costPriceCentSpecified() && existingSku != null
                ? existingSku.costPriceCent()
                : request.costPriceCent();
        java.math.BigDecimal volumeCubicMeter = !request.volumeCubicMeterSpecified() && existingSku != null
                ? existingSku.volumeCubicMeter()
                : request.volumeCubicMeter();
        Boolean defaultSelected = request.defaultSelected() == null && existingSku != null
                ? existingSku.defaultSelected()
                : request.defaultSelected();
        String combinationKey = request.combinationKey() == null && existingSku != null
                ? existingSku.combinationKey()
                : request.combinationKey();
        Integer lowStockThreshold = !request.lowStockThresholdSpecified() && existingSku != null
                ? existingSku.lowStockThreshold()
                : request.lowStockThreshold();
        AdminSkuUpsertRequest normalized = new AdminSkuUpsertRequest(
                request.id(),
                request.skuCode(),
                request.specJson(),
                request.specText(),
                request.priceCent(),
                request.originalPriceCent() == null ? 0L : request.originalPriceCent(),
                request.stockAvailable() == null ? 0 : request.stockAvailable(),
                request.weightGram(),
                costPriceCent,
                volumeCubicMeter,
                request.image(),
                imageFileId,
                StringUtils.hasText(request.status()) ? request.status() : SkuStatus.ENABLED.name(),
                request.sortOrder() == null ? index : request.sortOrder(),
                defaultSelected,
                combinationKey,
                specValueKeys,
                request.imageFileIdSpecified(),
                request.specValueKeysSpecified(),
                request.costPriceCentSpecified(),
                request.volumeCubicMeterSpecified()
        );
        normalized.setLowStockThreshold(lowStockThreshold == null ? 10 : lowStockThreshold);
        return normalized;
    }

    private AdminSkuUpsertRequest existingSkuRequest(ProductSku sku, boolean preserveSpecValueKeys) {
        AdminSkuUpsertRequest request = new AdminSkuUpsertRequest(
                sku.id(), sku.skuCode(), sku.specJson(), sku.specText(), sku.priceCent(), sku.originalPriceCent(),
                sku.stockAvailable(), sku.weightGram(), sku.costPriceCent(), sku.volumeCubicMeter(), sku.image(),
                sku.imageFileId(), sku.status(), sku.sortOrder(), sku.defaultSelected(), sku.combinationKey(),
                preserveSpecValueKeys ? findSkuSpecValueKeys(sku.id()) : List.of(), false, false, false, false
        );
        request.setLowStockThreshold(sku.lowStockThreshold());
        return request;
    }

    private void normalizeDefaultSku(List<AdminSkuUpsertRequest> skus) {
        AdminSkuUpsertRequest explicitDefault = null;
        for (AdminSkuUpsertRequest sku : skus) {
            if (!SkuStatus.ENABLED.name().equals(requireSkuStatus(sku.status()).name())) {
                sku.setDefaultSelected(false);
                continue;
            }
            if (Boolean.TRUE.equals(sku.defaultSelected())) {
                if (explicitDefault != null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
                explicitDefault = sku;
            }
        }
        if (explicitDefault == null) {
            skus.stream()
                    .filter(sku -> SkuStatus.ENABLED.name().equals(sku.status()))
                    .findFirst()
                    .ifPresent(sku -> sku.setDefaultSelected(true));
        }
    }

    private void upsertSkuRows(
            Long spuId,
            AdminSpuUpsertRequest product,
            Map<Long, ProductSku> existingSkusById,
            Map<String, PersistedSpecValue> specValuesByKey,
            String operatorType,
            Long operatorId
    ) {
        List<AdminSkuUpsertRequest> normalizedSkus = product.skus() == null ? List.of() : product.skus();
        Map<String, ProductSku> existingSkusByCode = new HashMap<>();
        Map<String, ProductSku> existingSkusByCombination = new HashMap<>();
        Map<String, ProductSku> existingSkusBySpecText = new HashMap<>();
        for (ProductSku existingSku : existingSkusById.values()) {
            existingSkusByCode.put(existingSku.skuCode(), existingSku);
            existingSkusByCombination.put(existingSku.combinationKey(), existingSku);
            existingSkusBySpecText.put(existingSku.specText(), existingSku);
        }
        Set<Long> retainedSkuIds = new HashSet<>();
        Set<String> retainedCombinationKeys = new HashSet<>();
        Set<String> retainedSpecTexts = new HashSet<>();
        for (AdminSkuUpsertRequest sku : normalizedSkus) {
            if (sku.id() != null && !existingSkusById.containsKey(sku.id())) {
                throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
            }
            SkuSnapshot snapshot = resolveSkuSnapshot(product, sku, specValuesByKey);
            if (!StringUtils.hasText(snapshot.specText()) || snapshot.specText().length() > 255
                    || !StringUtils.hasText(snapshot.combinationKey()) || snapshot.combinationKey().length() > 512
                    || !retainedCombinationKeys.add(snapshot.combinationKey())
                    || !retainedSpecTexts.add(snapshot.specText().toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            ProductSku existingSku = sku.id() == null ? null : existingSkusById.get(sku.id());
            if (existingSku == null && StringUtils.hasText(sku.skuCode())) {
                existingSku = existingSkusByCode.get(sku.skuCode().trim());
            }
            if (existingSku == null) {
                existingSku = existingSkusByCombination.get(snapshot.combinationKey());
            }
            if (existingSku == null) {
                existingSku = existingSkusBySpecText.get(snapshot.specText());
            }
            if (existingSku != null && retainedSkuIds.contains(existingSku.id())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            String skuCode = StringUtils.hasText(sku.skuCode())
                    ? sku.skuCode().trim()
                    : existingSku == null ? generateSkuCode(spuId) : existingSku.skuCode();
            ensureSkuCodeAvailable(skuCode, existingSku == null ? null : existingSku.id());
            Long skuId = existingSku == null
                    ? insertSku(spuId, sku, snapshot, skuCode)
                    : updateSku(existingSku.id(), spuId, sku, snapshot, skuCode);
            if (!retainedSkuIds.add(skuId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
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
            } else if (!existingSku.stockAvailable().equals(sku.stockAvailable())) {
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
            replaceSkuSpecValues(skuId, snapshot.specValueIds());
            sku.setSkuCode(skuCode);
            sku.setImage(snapshot.image());
            sku.setImageFileId(snapshot.imageFileId());
            syncSkuFileUsages(skuId, sku);
        }
        LocalDateTime deletedAt = LocalDateTime.now();
        for (ProductSku existingSku : existingSkusById.values()) {
            if (!retainedSkuIds.contains(existingSku.id()) && existingSku.deletedAt() == null) {
                jdbcClient.sql("""
                                update product_sku
                                set status = :status,
                                    is_default = false,
                                    deleted_at = :deletedAt,
                                    updated_at = :deletedAt
                                where id = :skuId
                                """)
                        .param("status", SkuStatus.DISABLED.name())
                        .param("deletedAt", deletedAt)
                        .param("skuId", existingSku.id())
                        .update();
                storageUsageService.removeOwnerUsages(StorageUsageOwnerType.PRODUCT_SKU, existingSku.id());
            }
        }
    }

    private Long insertSku(
            Long spuId,
            AdminSkuUpsertRequest request,
            SkuSnapshot snapshot,
            String skuCode
    ) {
        String status = requireSkuStatus(request.status()).name();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        INSERT INTO product_sku (
                            spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                            cost_price_cent, stock_available, low_stock_threshold, weight_gram, volume_cubic_meter,
                            image, image_file_id, status, is_default, combination_key, sort_order
                        )
                        VALUES (
                            :spuId, :skuCode, :specJson, :specText, :priceCent, :originalPriceCent,
                            :costPriceCent, :stockAvailable, :lowStockThreshold, :weightGram, :volumeCubicMeter,
                            :image, :imageFileId, :status, :isDefault, :combinationKey, :sortOrder
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("spuId", spuId)
                        .addValue("skuCode", skuCode)
                        .addValue("specJson", snapshot.specJson())
                        .addValue("specText", snapshot.specText())
                        .addValue("priceCent", request.priceCent())
                        .addValue("originalPriceCent", request.originalPriceCent())
                        .addValue("costPriceCent", request.costPriceCent())
                        .addValue("stockAvailable", request.stockAvailable())
                        .addValue("lowStockThreshold", request.lowStockThreshold())
                        .addValue("weightGram", request.weightGram())
                        .addValue("volumeCubicMeter", request.volumeCubicMeter())
                        .addValue("image", snapshot.image())
                        .addValue("imageFileId", snapshot.imageFileId())
                        .addValue("status", status)
                        .addValue("isDefault", Boolean.TRUE.equals(request.defaultSelected()))
                        .addValue("combinationKey", snapshot.combinationKey())
                        .addValue("sortOrder", request.sortOrder()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private Long updateSku(
            Long skuId,
            Long spuId,
            AdminSkuUpsertRequest request,
            SkuSnapshot snapshot,
            String skuCode
    ) {
        int updatedRows = jdbcClient.sql("""
                        update product_sku
                        set sku_code = :skuCode,
                            spec_json = :specJson,
                            spec_text = :specText,
                            price_cent = :priceCent,
                            original_price_cent = :originalPriceCent,
                            cost_price_cent = :costPriceCent,
                            stock_available = :stockAvailable,
                            low_stock_threshold = :lowStockThreshold,
                            weight_gram = :weightGram,
                            volume_cubic_meter = :volumeCubicMeter,
                            image = :image,
                            image_file_id = :imageFileId,
                            status = :status,
                            is_default = :isDefault,
                            combination_key = :combinationKey,
                            sort_order = :sortOrder,
                            deleted_at = null,
                            updated_at = :updatedAt
                        where id = :skuId and spu_id = :spuId
                        """)
                .param("skuCode", skuCode)
                .param("specJson", snapshot.specJson())
                .param("specText", snapshot.specText())
                .param("priceCent", request.priceCent())
                .param("originalPriceCent", request.originalPriceCent())
                .param("costPriceCent", request.costPriceCent())
                .param("stockAvailable", request.stockAvailable())
                .param("lowStockThreshold", request.lowStockThreshold())
                .param("weightGram", request.weightGram())
                .param("volumeCubicMeter", request.volumeCubicMeter())
                .param("image", snapshot.image())
                .param("imageFileId", snapshot.imageFileId())
                .param("status", requireSkuStatus(request.status()).name())
                .param("isDefault", Boolean.TRUE.equals(request.defaultSelected()))
                .param("combinationKey", snapshot.combinationKey())
                .param("sortOrder", request.sortOrder())
                .param("updatedAt", LocalDateTime.now())
                .param("skuId", skuId)
                .param("spuId", spuId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.SKU_UNAVAILABLE);
        }
        return skuId;
    }

    private SkuSnapshot resolveSkuSnapshot(
            AdminSpuUpsertRequest product,
            AdminSkuUpsertRequest sku,
            Map<String, PersistedSpecValue> specValuesByKey
    ) {
        List<String> requestedKeys = sku.specValueKeys();
        if (requestedKeys.isEmpty() || specValuesByKey.isEmpty()) {
            String specJson = StringUtils.hasText(sku.specJson()) ? sku.specJson() : "{}";
            String specText = StringUtils.hasText(sku.specText()) ? sku.specText() : "默认";
            String combinationKey;
            if (StringUtils.hasText(sku.combinationKey())) {
                combinationKey = sku.combinationKey().trim();
            } else if (ProductSpecType.SINGLE.name().equals(product.specType())) {
                combinationKey = "SINGLE";
            } else {
                combinationKey = "legacy-" + UUID.nameUUIDFromBytes(
                        (specJson + "\u0000" + specText).getBytes(StandardCharsets.UTF_8)
                );
            }
            String image = defaultString(sku.image());
            Long imageFileId = sku.imageFileId();
            if (!StringUtils.hasText(image)) {
                image = product.mainImage();
                imageFileId = product.mainImageFileId();
            }
            return new SkuSnapshot(specJson, specText, combinationKey, defaultString(image), imageFileId, List.of());
        }

        Set<String> uniqueKeys = new LinkedHashSet<>(requestedKeys);
        if (uniqueKeys.size() != requestedKeys.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<PersistedSpecValue> values = uniqueKeys.stream()
                .map(key -> {
                    PersistedSpecValue value = specValuesByKey.get(key);
                    if (value == null) {
                        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                    }
                    return value;
                })
                .sorted((left, right) -> {
                    int groupSortComparison = Integer.compare(left.groupSortOrder(), right.groupSortOrder());
                    if (groupSortComparison != 0) {
                        return groupSortComparison;
                    }
                    return left.groupKey().compareTo(right.groupKey());
                })
                .toList();
        if (values.stream().map(PersistedSpecValue::groupKey).distinct().count() != product.specGroups().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Map<String, String> specMap = new LinkedHashMap<>();
        for (PersistedSpecValue value : values) {
            if (specMap.put(value.groupName(), value.valueName()) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        String specJson;
        try {
            specJson = objectMapper.writeValueAsString(specMap);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to create SKU specification snapshot", ex);
        }
        String specText = String.join(" / ", values.stream().map(PersistedSpecValue::valueName).toList());
        String combinationKey = String.join("|", uniqueKeys.stream().sorted().toList());
        String image = defaultString(sku.image());
        Long imageFileId = sku.imageFileId();
        if (!StringUtils.hasText(image)) {
            PersistedSpecValue fallbackValue = values.stream()
                    .filter(value -> StringUtils.hasText(value.image()))
                    .findFirst()
                    .orElse(null);
            if (fallbackValue == null) {
                image = product.mainImage();
                imageFileId = product.mainImageFileId();
            } else {
                image = fallbackValue.image();
                imageFileId = fallbackValue.imageFileId();
            }
        }
        return new SkuSnapshot(
                specJson,
                specText,
                combinationKey,
                defaultString(image),
                imageFileId,
                values.stream().map(PersistedSpecValue::id).toList()
        );
    }

    private void replaceSkuSpecValues(Long skuId, List<Long> specValueIds) {
        jdbcClient.sql("delete from product_sku_spec_value where sku_id = :skuId")
                .param("skuId", skuId)
                .update();
        for (Long specValueId : specValueIds) {
            jdbcClient.sql("""
                            insert into product_sku_spec_value (sku_id, spec_value_id)
                            values (:skuId, :specValueId)
                            """)
                    .param("skuId", skuId)
                    .param("specValueId", specValueId)
                    .update();
        }
    }

    private List<String> findSkuSpecValueKeys(Long skuId) {
        return jdbcClient.sql("""
                        select v.value_key
                        from product_sku_spec_value sv
                        join product_spu_spec_value v on v.id = sv.spec_value_id
                        join product_spu_spec_group g on g.id = v.group_id
                        where sv.sku_id = :skuId
                          and v.deleted_at is null
                          and g.deleted_at is null
                        order by g.sort_order, g.id, v.sort_order, v.id
                        """)
                .param("skuId", skuId)
                .query(String.class)
                .list();
    }

    private List<AdminSpuSpecGroupUpsertRequest> findActiveSpecGroupRequests(Long spuId) {
        return jdbcClient.sql("""
                        select id, group_key, name, image_enabled, sort_order
                        from product_spu_spec_group
                        where spu_id = :spuId and deleted_at is null
                        order by sort_order, id
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> {
                    Long groupId = rs.getLong("id");
                    List<AdminSpuSpecValueUpsertRequest> values = jdbcClient.sql("""
                                    select id, value_key, value_name, image, image_file_id, sort_order
                                    from product_spu_spec_value
                                    where group_id = :groupId and deleted_at is null
                                    order by sort_order, id
                                    """)
                            .param("groupId", groupId)
                            .query((valueRs, valueRowNum) -> new AdminSpuSpecValueUpsertRequest(
                                    valueRs.getLong("id"),
                                    valueRs.getString("value_key"),
                                    valueRs.getString("value_name"),
                                    valueRs.getString("image"),
                                    valueRs.getObject("image_file_id", Long.class),
                                    valueRs.getInt("sort_order"),
                                    false
                            ))
                            .list();
                    return new AdminSpuSpecGroupUpsertRequest(
                            groupId,
                            rs.getString("group_key"),
                            rs.getString("name"),
                            rs.getBoolean("image_enabled"),
                            rs.getInt("sort_order"),
                            values
                    );
                })
                .list();
    }

    private List<String> findProductTags(Long spuId) {
        return jdbcClient.sql("""
                        select tag_code
                        from product_spu_tag
                        where spu_id = :spuId
                        order by tag_code
                        """)
                .param("spuId", spuId)
                .query(String.class)
                .list();
    }

    private List<Long> findGuaranteeServiceIds(Long spuId) {
        return jdbcClient.sql("""
                        select service_id
                        from product_spu_guarantee_service
                        where spu_id = :spuId
                        order by sort_order, service_id
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list();
    }

    private Map<String, PersistedSpecValue> findPersistedSpecValuesByKey(Long spuId) {
        List<PersistedSpecValue> values = jdbcClient.sql("""
                        select v.id,
                               g.group_key,
                               g.name as group_name,
                               v.value_key,
                               v.value_name,
                               v.image,
                               v.image_file_id,
                               g.sort_order as group_sort_order,
                               v.sort_order as value_sort_order
                        from product_spu_spec_value v
                        join product_spu_spec_group g on g.id = v.group_id
                        where g.spu_id = :spuId
                          and g.deleted_at is null
                          and v.deleted_at is null
                        order by g.sort_order, g.id, v.sort_order, v.id
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new PersistedSpecValue(
                        rs.getLong("id"),
                        rs.getString("group_key"),
                        rs.getString("group_name"),
                        rs.getString("value_key"),
                        rs.getString("value_name"),
                        defaultString(rs.getString("image")),
                        rs.getObject("image_file_id", Long.class),
                        rs.getInt("group_sort_order"),
                        rs.getInt("value_sort_order")
                ))
                .list();
        Map<String, PersistedSpecValue> valuesByKey = new LinkedHashMap<>();
        for (PersistedSpecValue value : values) {
            if (valuesByKey.put(value.valueKey(), value) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        return Map.copyOf(valuesByKey);
    }

    private String generateSkuCode(Long spuId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = "SKU-" + spuId + "-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 10).toUpperCase();
            if (isSkuCodeAvailable(code, null)) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate unique SKU code");
    }

    private void ensureSkuCodeAvailable(String skuCode, Long currentSkuId) {
        if (!StringUtils.hasText(skuCode) || skuCode.length() > 64 || !isSkuCodeAvailable(skuCode, currentSkuId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean isSkuCodeAvailable(String skuCode, Long currentSkuId) {
        Long count = jdbcClient.sql("""
                        select count(*)
                        from product_sku
                        where sku_code = :skuCode
                          and (:currentSkuId is null or id <> :currentSkuId)
                        """)
                .param("skuCode", skuCode)
                .param("currentSkuId", currentSkuId)
                .query(Long.class)
                .single();
        return count != null && count == 0;
    }

    private Map<String, PersistedSpecValue> replaceSpecRows(Long spuId, AdminSpuUpsertRequest request) {
        if (ProductSpecType.SINGLE.name().equals(request.specType()) || request.specGroups().isEmpty()) {
            softDeleteAllSpecRows(spuId);
            return Map.of();
        }

        Map<Long, SpecGroupRow> existingGroupsById = findSpecGroups(spuId).stream()
                .collect(java.util.stream.Collectors.toMap(SpecGroupRow::id, row -> row));
        Map<String, SpecGroupRow> existingGroupsByKey = new HashMap<>();
        for (SpecGroupRow group : existingGroupsById.values()) {
            existingGroupsByKey.put(group.groupKey(), group);
        }
        Set<Long> retainedGroupIds = new HashSet<>();
        Map<String, PersistedSpecValue> persistedValuesByKey = new LinkedHashMap<>();
        for (int groupIndex = 0; groupIndex < request.specGroups().size(); groupIndex++) {
            AdminSpuSpecGroupUpsertRequest group = request.specGroups().get(groupIndex);
            SpecGroupRow existingGroup = group.id() == null
                    ? existingGroupsByKey.get(group.groupKey())
                    : existingGroupsById.get(group.id());
            if (group.id() != null && existingGroup == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (existingGroup != null && !existingGroup.groupKey().equals(group.groupKey())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            int groupSortOrder = group.sortOrder() == null ? groupIndex : group.sortOrder();
            Long groupId = existingGroup == null
                    ? insertSpecGroup(spuId, group, groupSortOrder)
                    : updateSpecGroup(existingGroup.id(), group, groupSortOrder);
            retainedGroupIds.add(groupId);
            replaceSpecValueRows(groupId, group, groupSortOrder, persistedValuesByKey);
        }

        LocalDateTime deletedAt = LocalDateTime.now();
        for (SpecGroupRow existingGroup : existingGroupsById.values()) {
            if (!retainedGroupIds.contains(existingGroup.id()) && existingGroup.deletedAt() == null) {
                softDeleteSpecGroup(existingGroup.id(), deletedAt);
            }
        }
        return Map.copyOf(persistedValuesByKey);
    }

    private Long insertSpecGroup(Long spuId, AdminSpuSpecGroupUpsertRequest group, int sortOrder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into product_spu_spec_group
                            (spu_id, group_key, name, image_enabled, sort_order)
                        values
                            (:spuId, :groupKey, :name, :imageEnabled, :sortOrder)
                        """,
                new MapSqlParameterSource()
                        .addValue("spuId", spuId)
                        .addValue("groupKey", group.groupKey())
                        .addValue("name", group.name().trim())
                        .addValue("imageEnabled", Boolean.TRUE.equals(group.imageEnabled()))
                        .addValue("sortOrder", sortOrder),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private Long updateSpecGroup(Long groupId, AdminSpuSpecGroupUpsertRequest group, int sortOrder) {
        jdbcClient.sql("""
                        update product_spu_spec_group
                        set name = :name,
                            image_enabled = :imageEnabled,
                            sort_order = :sortOrder,
                            deleted_at = null,
                            updated_at = :updatedAt
                        where id = :groupId
                        """)
                .param("name", group.name().trim())
                .param("imageEnabled", Boolean.TRUE.equals(group.imageEnabled()))
                .param("sortOrder", sortOrder)
                .param("updatedAt", LocalDateTime.now())
                .param("groupId", groupId)
                .update();
        return groupId;
    }

    private void replaceSpecValueRows(
            Long groupId,
            AdminSpuSpecGroupUpsertRequest group,
            int groupSortOrder,
            Map<String, PersistedSpecValue> persistedValuesByKey
    ) {
        List<SpecValueRow> existingValues = findSpecValues(groupId);
        Map<Long, SpecValueRow> existingValuesById = new HashMap<>();
        Map<String, SpecValueRow> existingValuesByKey = new HashMap<>();
        for (SpecValueRow value : existingValues) {
            existingValuesById.put(value.id(), value);
            existingValuesByKey.put(value.valueKey(), value);
        }
        Set<Long> retainedValueIds = new HashSet<>();
        for (int valueIndex = 0; valueIndex < group.values().size(); valueIndex++) {
            AdminSpuSpecValueUpsertRequest value = group.values().get(valueIndex);
            SpecValueRow existingValue = value.id() == null
                    ? existingValuesByKey.get(value.valueKey())
                    : existingValuesById.get(value.id());
            if (value.id() != null && existingValue == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (existingValue != null && !existingValue.valueKey().equals(value.valueKey())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            int valueSortOrder = value.sortOrder() == null ? valueIndex : value.sortOrder();
            Long imageFileId = value.imageFileId();
            if (!value.imageFileIdSpecified() && imageFileId == null && existingValue != null
                    && sameUrlSnapshot(value.image(), existingValue.image())) {
                imageFileId = existingValue.imageFileId();
            }
            Long valueId = existingValue == null
                    ? insertSpecValue(groupId, value, imageFileId, valueSortOrder)
                    : updateSpecValue(existingValue.id(), value, imageFileId, valueSortOrder);
            retainedValueIds.add(valueId);
            syncSpecValueFileUsages(valueId, value.valueName(), value.image(), imageFileId);
            PersistedSpecValue persistedValue = new PersistedSpecValue(
                    valueId, group.groupKey(), group.name(), value.valueKey(), value.valueName(),
                    defaultString(value.image()), imageFileId, groupSortOrder, valueSortOrder
            );
            if (persistedValuesByKey.put(value.valueKey(), persistedValue) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        LocalDateTime deletedAt = LocalDateTime.now();
        for (SpecValueRow existingValue : existingValues) {
            if (!retainedValueIds.contains(existingValue.id()) && existingValue.deletedAt() == null) {
                jdbcClient.sql("""
                                update product_spu_spec_value
                                set deleted_at = :deletedAt, updated_at = :deletedAt
                                where id = :valueId
                                """)
                        .param("deletedAt", deletedAt)
                        .param("valueId", existingValue.id())
                        .update();
                storageUsageService.removeOwnerUsages(StorageUsageOwnerType.PRODUCT_SPEC_VALUE, existingValue.id());
            }
        }
    }

    private Long insertSpecValue(
            Long groupId,
            AdminSpuSpecValueUpsertRequest value,
            Long imageFileId,
            int sortOrder
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into product_spu_spec_value
                            (group_id, value_key, value_name, image, image_file_id, sort_order)
                        values
                            (:groupId, :valueKey, :valueName, :image, :imageFileId, :sortOrder)
                        """,
                new MapSqlParameterSource()
                        .addValue("groupId", groupId)
                        .addValue("valueKey", value.valueKey())
                        .addValue("valueName", value.valueName().trim())
                        .addValue("image", defaultString(value.image()))
                        .addValue("imageFileId", imageFileId)
                        .addValue("sortOrder", sortOrder),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    private Long updateSpecValue(
            Long valueId,
            AdminSpuSpecValueUpsertRequest value,
            Long imageFileId,
            int sortOrder
    ) {
        jdbcClient.sql("""
                        update product_spu_spec_value
                        set value_name = :valueName,
                            image = :image,
                            image_file_id = :imageFileId,
                            sort_order = :sortOrder,
                            deleted_at = null,
                            updated_at = :updatedAt
                        where id = :valueId
                        """)
                .param("valueName", value.valueName().trim())
                .param("image", defaultString(value.image()))
                .param("imageFileId", imageFileId)
                .param("sortOrder", sortOrder)
                .param("updatedAt", LocalDateTime.now())
                .param("valueId", valueId)
                .update();
        return valueId;
    }

    private List<SpecGroupRow> findSpecGroups(Long spuId) {
        return jdbcClient.sql("""
                        select id, group_key, deleted_at
                        from product_spu_spec_group
                        where spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new SpecGroupRow(
                        rs.getLong("id"),
                        rs.getString("group_key"),
                        rs.getObject("deleted_at", LocalDateTime.class)
                ))
                .list();
    }

    private List<SpecValueRow> findSpecValues(Long groupId) {
        return jdbcClient.sql("""
                        select id, value_key, image, image_file_id, deleted_at
                        from product_spu_spec_value
                        where group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query((rs, rowNum) -> new SpecValueRow(
                        rs.getLong("id"),
                        rs.getString("value_key"),
                        rs.getString("image"),
                        rs.getObject("image_file_id", Long.class),
                        rs.getObject("deleted_at", LocalDateTime.class)
                ))
                .list();
    }

    private void softDeleteAllSpecRows(Long spuId) {
        LocalDateTime deletedAt = LocalDateTime.now();
        for (SpecGroupRow group : findSpecGroups(spuId)) {
            if (group.deletedAt() == null) {
                softDeleteSpecGroup(group.id(), deletedAt);
            }
        }
    }

    private void softDeleteSpecGroup(Long groupId, LocalDateTime deletedAt) {
        List<SpecValueRow> values = findSpecValues(groupId);
        jdbcClient.sql("""
                        update product_spu_spec_value
                        set deleted_at = :deletedAt, updated_at = :deletedAt
                        where group_id = :groupId and deleted_at is null
                        """)
                .param("deletedAt", deletedAt)
                .param("groupId", groupId)
                .update();
        jdbcClient.sql("""
                        update product_spu_spec_group
                        set deleted_at = :deletedAt, updated_at = :deletedAt
                        where id = :groupId and deleted_at is null
                        """)
                .param("deletedAt", deletedAt)
                .param("groupId", groupId)
                .update();
        for (SpecValueRow value : values) {
            storageUsageService.removeOwnerUsages(StorageUsageOwnerType.PRODUCT_SPEC_VALUE, value.id());
        }
    }

    private void syncSpecValueFileUsages(Long valueId, String label, String image, Long imageFileId) {
        if (imageFileId != null) {
            storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.IMAGE);
        }
        List<StorageUsageService.UsageAssignment> usages = imageFileId == null
                ? List.of()
                : List.of(new StorageUsageService.UsageAssignment(
                imageFileId,
                StorageFileUsageType.PRODUCT_SPEC_VALUE_IMAGE,
                defaultString(image),
                1,
                false
        ));
        storageUsageService.replaceOwnerUsages(StorageUsageOwnerType.PRODUCT_SPEC_VALUE, valueId, label, usages);
    }

    private void replaceProductAssociations(Long spuId, AdminSpuUpsertRequest request) {
        if (request.tagsSpecified()) {
            jdbcClient.sql("delete from product_spu_tag where spu_id = :spuId").param("spuId", spuId).update();
            Set<String> tags = new LinkedHashSet<>();
            for (String tag : request.tags()) {
                String normalizedTag = parseEnum(tag, ProductTag.class, ErrorCode.VALIDATION_FAILED).name();
                if (tags.add(normalizedTag)) {
                    jdbcClient.sql("insert into product_spu_tag (spu_id, tag_code) values (:spuId, :tagCode)")
                            .param("spuId", spuId)
                            .param("tagCode", normalizedTag)
                            .update();
                }
            }
        }

        if (request.guaranteeServiceIdsSpecified()) {
            List<Long> serviceIds = new java.util.ArrayList<>(new LinkedHashSet<>(request.guaranteeServiceIds()));
            jdbcClient.sql("delete from product_spu_guarantee_service where spu_id = :spuId")
                    .param("spuId", spuId)
                    .update();
            int serviceSortOrder = 0;
            for (Long serviceId : serviceIds) {
                jdbcClient.sql("""
                                insert into product_spu_guarantee_service (spu_id, service_id, sort_order)
                                values (:spuId, :serviceId, :sortOrder)
                                """)
                        .param("spuId", spuId)
                        .param("serviceId", serviceId)
                        .param("sortOrder", serviceSortOrder++)
                        .update();
            }
        }

    }

    private void lockRequestedGuaranteeServices(AdminSpuUpsertRequest request) {
        if (!request.guaranteeServiceIdsSpecified()) {
            return;
        }
        request.guaranteeServiceIds().stream()
                .distinct()
                .sorted(java.util.Comparator.nullsFirst(Long::compareTo))
                .forEach(this::lockActiveGuaranteeService);
    }

    private void requireStockWritePermission(
            AdminSpuUpsertRequest request,
            Map<Long, ProductSku> existingSkusById,
            boolean stockWriteAllowed
    ) {
        if (stockWriteAllowed) {
            return;
        }
        Map<String, ProductSku> existingSkusByCode = new HashMap<>();
        Map<String, ProductSku> existingSkusByCombination = new HashMap<>();
        Map<String, ProductSku> existingSkusBySpecText = new HashMap<>();
        for (ProductSku existingSku : existingSkusById.values()) {
            existingSkusByCode.put(existingSku.skuCode(), existingSku);
            existingSkusByCombination.put(existingSku.combinationKey(), existingSku);
            existingSkusBySpecText.put(existingSku.specText(), existingSku);
        }
        for (AdminSkuUpsertRequest sku : request.skus()) {
            ProductSku existingSku = sku.id() == null ? null : existingSkusById.get(sku.id());
            if (existingSku == null && StringUtils.hasText(sku.skuCode())) {
                existingSku = existingSkusByCode.get(sku.skuCode().trim());
            }
            if (existingSku == null) {
                String combinationKey = requestedCombinationKey(request.specType(), sku);
                if (combinationKey != null) {
                    existingSku = existingSkusByCombination.get(combinationKey);
                }
            }
            if (existingSku == null && StringUtils.hasText(sku.specText())) {
                existingSku = existingSkusBySpecText.get(sku.specText());
            }
            if (existingSku == null) {
                if (sku.stockAvailable() != null && sku.stockAvailable() != 0) {
                    throw new BusinessException(ErrorCode.PERMISSION_DENIED);
                }
                if (sku.lowStockThreshold() != null && sku.lowStockThreshold() != 10) {
                    throw new BusinessException(ErrorCode.PERMISSION_DENIED);
                }
            } else if (existingSku.deletedAt() != null
                    || !Objects.equals(existingSku.stockAvailable(), sku.stockAvailable())
                    || !Objects.equals(existingSku.lowStockThreshold(), sku.lowStockThreshold())) {
                throw new BusinessException(ErrorCode.PERMISSION_DENIED);
            }
        }
    }

    private void lockActiveGuaranteeService(Long serviceId) {
        if (serviceId == null || serviceId <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        jdbcClient.sql("""
                        select id
                        from product_guarantee_service
                        where id = :serviceId
                          and deleted_at is null
                        for update
                        """)
                .param("serviceId", serviceId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private String requestedCombinationKey(String specType, AdminSkuUpsertRequest sku) {
        if (StringUtils.hasText(sku.combinationKey())) {
            return sku.combinationKey().trim();
        }
        if (ProductSpecType.SINGLE.name().equals(specType)) {
            return "SINGLE";
        }
        if (!sku.specValueKeys().isEmpty()) {
            return String.join("|", sku.specValueKeys().stream().sorted().toList());
        }
        return null;
    }

    private void validateProductAggregate(AdminSpuUpsertRequest request, boolean publishing) {
        ProductSpecType specType = requireSpecType(request.specType());
        List<AdminSkuUpsertRequest> skus = request.skus() == null ? List.of() : request.skus();
        if (skus.size() > MAX_SKU_COMBINATIONS || request.virtualSales() == null || request.virtualSales() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long enabledSkuCount = 0;
        long defaultSkuCount = 0;
        for (AdminSkuUpsertRequest sku : skus) {
            SkuStatus status = requireSkuStatus(sku.status());
            if (sku.priceCent() == null || sku.priceCent() <= 0
                    || sku.originalPriceCent() == null || sku.originalPriceCent() < 0
                    || sku.stockAvailable() == null || sku.stockAvailable() < 0
                    || (sku.costPriceCent() != null && sku.costPriceCent() < 0)
                    || (sku.weightGram() != null && sku.weightGram() < 0)
                    || (sku.volumeCubicMeter() != null && sku.volumeCubicMeter().signum() < 0)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (status == SkuStatus.ENABLED) {
                enabledSkuCount++;
                if (Boolean.TRUE.equals(sku.defaultSelected())) {
                    defaultSkuCount++;
                }
            }
        }
        if (enabledSkuCount > 0 && defaultSkuCount != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (specType == ProductSpecType.SINGLE) {
            if (skus.size() > 1 || !request.specGroups().isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (skus.size() == 1) {
                skus.get(0).setDefaultSelected(SkuStatus.ENABLED.name().equals(skus.get(0).status()));
            }
        } else if (!request.specGroups().isEmpty() || skus.stream().anyMatch(sku -> !sku.specValueKeys().isEmpty())) {
            validateStructuredMultiSpec(request, skus);
        }
        for (String tag : request.tags()) {
            parseEnum(tag, ProductTag.class, ErrorCode.VALIDATION_FAILED);
        }
        if (publishing && (!StringUtils.hasText(request.title())
                || !StringUtils.hasText(request.mainImage())
                || enabledSkuCount == 0
                || defaultSkuCount != 1)) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    private void validateStructuredMultiSpec(AdminSpuUpsertRequest request, List<AdminSkuUpsertRequest> skus) {
        if (request.specGroups().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> groupKeys = new HashSet<>();
        Set<String> valueKeys = new HashSet<>();
        Set<String> normalizedValueKeys = new HashSet<>();
        long imageGroupCount = 0;
        long combinationCount = 1;
        for (AdminSpuSpecGroupUpsertRequest group : request.specGroups()) {
            String normalizedGroupKey = defaultString(group.groupKey()).toLowerCase(Locale.ROOT);
            if (!isValidSpecKey(group.groupKey()) || !StringUtils.hasText(group.name()) || group.name().trim().length() > 30
                    || group.values().isEmpty() || !groupKeys.add(normalizedGroupKey)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (Boolean.TRUE.equals(group.imageEnabled())) {
                imageGroupCount++;
            }
            Set<String> groupValueKeys = new HashSet<>();
            Set<String> groupValueNames = new HashSet<>();
            for (AdminSpuSpecValueUpsertRequest value : group.values()) {
                String normalizedValueKey = defaultString(value.valueKey()).toLowerCase(Locale.ROOT);
                if (!isValidSpecKey(value.valueKey()) || !StringUtils.hasText(value.valueName())
                        || value.valueName().trim().length() > 30
                        || !groupValueKeys.add(normalizedValueKey)
                        || !normalizedValueKeys.add(normalizedValueKey)
                        || !valueKeys.add(value.valueKey())
                        || !groupValueNames.add(value.valueName().trim().toLowerCase(Locale.ROOT))) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            }
            combinationCount *= group.values().size();
            if (combinationCount > MAX_SKU_COMBINATIONS) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        if (imageGroupCount != 1 || skus.size() != combinationCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<String> combinations = new HashSet<>();
        for (AdminSkuUpsertRequest sku : skus) {
            if (sku.specValueKeys().size() != request.specGroups().size()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            Set<String> keys = new HashSet<>(sku.specValueKeys());
            Set<String> normalizedKeys = new HashSet<>();
            for (String key : keys) {
                normalizedKeys.add(defaultString(key).toLowerCase(Locale.ROOT));
            }
            if (keys.size() != request.specGroups().size()
                    || normalizedKeys.size() != request.specGroups().size()
                    || !valueKeys.containsAll(keys)
                    || !combinations.add(String.join("|", normalizedKeys.stream().sorted().toList()))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private boolean hasValidMultiSpecShape(Long spuId) {
        Long groupCount = jdbcClient.sql("""
                        select count(*)
                        from product_spu_spec_group
                        where spu_id = :spuId and deleted_at is null
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        if (groupCount == null || groupCount == 0) {
            return true;
        }
        Long imageGroupCount = jdbcClient.sql("""
                        select count(*)
                        from product_spu_spec_group
                        where spu_id = :spuId and deleted_at is null and image_enabled = true
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        Long emptyGroupCount = jdbcClient.sql("""
                        select count(*)
                        from product_spu_spec_group g
                        where g.spu_id = :spuId
                          and g.deleted_at is null
                          and not exists (
                              select 1 from product_spu_spec_value v
                              where v.group_id = g.id and v.deleted_at is null
                          )
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        return imageGroupCount != null && imageGroupCount == 1
                && emptyGroupCount != null && emptyGroupCount == 0;
    }

    private boolean isValidSpecKey(String key) {
        return key != null && SPEC_KEY_PATTERN.matcher(key).matches();
    }

    private ProductSpecType requireSpecType(String specType) {
        return parseEnum(specType, ProductSpecType.class, ErrorCode.VALIDATION_FAILED);
    }

    private void requireFreightTemplate(Long freightTemplateId, boolean enabledRequired) {
        if (freightTemplateId == null) {
            throw new BusinessException(enabledRequired ? ErrorCode.PRODUCT_UNAVAILABLE : ErrorCode.VALIDATION_FAILED);
        }
        String status = jdbcClient.sql("""
                        select status
                        from freight_template
                        where id = :templateId and deleted_at is null
                        for update
                        """)
                .param("templateId", freightTemplateId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        enabledRequired ? ErrorCode.PRODUCT_UNAVAILABLE : ErrorCode.VALIDATION_FAILED
                ));
        if (enabledRequired && !"ENABLED".equals(status)) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    private void syncCategoryFileUsages(Long categoryId, AdminCategoryRequest request) {
        if (request.iconFileId() != null) {
            storageUsageService.requireActivePublicMedia(request.iconFileId(), StorageMediaKind.IMAGE);
        }
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
            storageUsageService.requireActivePublicMedia(request.mainImageFileId(), StorageMediaKind.IMAGE);
            putUsage(dedupedUsages, request.mainImageFileId(), StorageFileUsageType.PRODUCT_SPU_MAIN, request.mainImage(), 1);
        }
        if (request.mainVideoFileId() != null) {
            storageUsageService.requireActivePublicMedia(request.mainVideoFileId(), StorageMediaKind.VIDEO);
            putUsage(dedupedUsages, request.mainVideoFileId(), StorageFileUsageType.PRODUCT_SPU_VIDEO, request.mainVideo(), 2);
        }
        List<AdminProductImageUpsertRequest> gallery = request.images() == null ? List.of() : request.images();
        for (int index = 0; index < gallery.size(); index++) {
            AdminProductImageUpsertRequest image = gallery.get(index);
            if (image.fileId() != null) {
                storageUsageService.requireActivePublicMedia(image.fileId(), StorageMediaKind.IMAGE);
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
        if (request.imageFileId() != null) {
            storageUsageService.requireActivePublicMedia(request.imageFileId(), StorageMediaKind.IMAGE);
        }
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
                        from storage_asset
                        where scope = 'LIBRARY'
                          and media_kind = 'IMAGE'
                          and status = 'ACTIVE'
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
                        SELECT id, category_id, title, subtitle, main_image, main_image_file_id,
                               main_video, main_video_file_id, spec_type, freight_template_id, virtual_sales,
                               selling_points, detail_html, sort_order, status, deleted_at, purged_at, created_at, updated_at
                        FROM product_spu
                        WHERE id = :spuId
                        """)
                .param("spuId", spuId)
                .query(this::mapSpu)
                .optional();
    }

    private Optional<ProductSpu> findSpuForUpdate(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, category_id, title, subtitle, main_image, main_image_file_id,
                               main_video, main_video_file_id, spec_type, freight_template_id, virtual_sales,
                               selling_points, detail_html, sort_order, status, deleted_at, purged_at, created_at, updated_at
                        FROM product_spu
                        WHERE id = :spuId AND deleted_at IS NULL AND purged_at IS NULL
                        FOR UPDATE
                        """)
                .param("spuId", spuId)
                .query(this::mapSpu)
                .optional();
    }

    private Optional<ProductSpu> findRecycledSpuForUpdate(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, category_id, title, subtitle, main_image, main_image_file_id,
                               main_video, main_video_file_id, spec_type, freight_template_id, virtual_sales,
                               selling_points, detail_html, sort_order, status, deleted_at, purged_at, created_at, updated_at
                        FROM product_spu
                        WHERE id = :spuId AND deleted_at IS NOT NULL AND purged_at IS NULL
                        FOR UPDATE
                        """)
                .param("spuId", spuId)
                .query(this::mapSpu)
                .optional();
    }

    private List<ProductSpuImage> findSpuImagesBySpuId(Long spuId) {
        return jdbcClient.sql("""
                        select id, spu_id, url, file_id, sort_order, created_at
                        from product_spu_image
                        where spu_id = :spuId
                        order by sort_order asc, id asc
                        """)
                .param("spuId", spuId)
                .query(this::mapSpuImage)
                .list();
    }

    private List<ProductSku> findSkusBySpuId(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               cost_price_cent, stock_available, low_stock_threshold, weight_gram, volume_cubic_meter,
                               image, image_file_id, status, is_default, combination_key, sort_order,
                               deleted_at, created_at, updated_at
                        FROM product_sku
                        WHERE spu_id = :spuId
                        ORDER BY sort_order ASC, id ASC
                        """)
                .param("spuId", spuId)
                .query(this::mapSku)
                .list();
    }

    private List<ProductSku> findSkusBySpuIdForUpdate(Long spuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               cost_price_cent, stock_available, low_stock_threshold, weight_gram, volume_cubic_meter,
                               image, image_file_id, status, is_default, combination_key, sort_order,
                               deleted_at, created_at, updated_at
                        FROM product_sku
                        WHERE spu_id = :spuId
                        ORDER BY sort_order ASC, id ASC
                        FOR UPDATE
                        """)
                .param("spuId", spuId)
                .query(this::mapSku)
                .list();
    }

    private Optional<ProductSku> findSku(Long skuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               cost_price_cent, stock_available, low_stock_threshold, weight_gram, volume_cubic_meter,
                               image, image_file_id, status, is_default, combination_key, sort_order,
                               deleted_at, created_at, updated_at
                        FROM product_sku
                        WHERE id = :skuId
                        """)
                .param("skuId", skuId)
                .query(this::mapSku)
                .optional();
    }

    private Optional<Long> findSkuSpuId(Long skuId) {
        return jdbcClient.sql("select spu_id from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Long.class)
                .optional();
    }

    private Optional<ProductSku> findSkuForUpdate(Long skuId) {
        return jdbcClient.sql("""
                        SELECT id, spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               cost_price_cent, stock_available, low_stock_threshold, weight_gram, volume_cubic_meter,
                               image, image_file_id, status, is_default, combination_key, sort_order,
                               deleted_at, created_at, updated_at
                        FROM product_sku
                        WHERE id = :skuId AND deleted_at IS NULL
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

    private boolean sameUrlSnapshot(String requestUrl, String existingUrl) {
        return defaultString(requestUrl).equals(defaultString(existingUrl));
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
                rs.getString("main_video"),
                rs.getObject("main_video_file_id", Long.class),
                rs.getString("spec_type"),
                rs.getObject("freight_template_id", Long.class),
                rs.getLong("virtual_sales"),
                rs.getString("selling_points"),
                rs.getString("detail_html"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("deleted_at", LocalDateTime.class),
                rs.getObject("purged_at", LocalDateTime.class),
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
                rs.getObject("cost_price_cent", Long.class),
                rs.getInt("stock_available"),
                rs.getInt("low_stock_threshold"),
                rs.getObject("weight_gram", Integer.class),
                rs.getBigDecimal("volume_cubic_meter"),
                rs.getString("image"),
                rs.getObject("image_file_id", Long.class),
                rs.getString("status"),
                rs.getBoolean("is_default"),
                rs.getString("combination_key"),
                rs.getInt("sort_order"),
                rs.getObject("deleted_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private ProductSpuImage mapSpuImage(ResultSet rs, int rowNum) throws SQLException {
        return new ProductSpuImage(
                rs.getLong("id"),
                rs.getLong("spu_id"),
                rs.getString("url"),
                rs.getObject("file_id", Long.class),
                rs.getInt("sort_order"),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private record SkuSnapshot(
            String specJson,
            String specText,
            String combinationKey,
            String image,
            Long imageFileId,
            List<Long> specValueIds
    ) {
    }

    private record PersistedSpecValue(
            Long id,
            String groupKey,
            String groupName,
            String valueKey,
            String valueName,
            String image,
            Long imageFileId,
            Integer groupSortOrder,
            Integer valueSortOrder
    ) {
    }

    private record SpecGroupRow(Long id, String groupKey, LocalDateTime deletedAt) {
    }

    private record SpecValueRow(
            Long id,
            String valueKey,
            String image,
            Long imageFileId,
            LocalDateTime deletedAt
    ) {
    }

    private record ResolvedStorageFile(Long fileId, String publicUrl) {
    }

    private record RestorableSpecValue(Long id, String name, String image, Long imageFileId) {
    }

    private record ProductBannerReference(Long id, String status) {
    }

    private record HomeProductReference(Long id, String status) {
    }
}
