package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.HomeBannerStatus;
import org.muybaby.shopserver.content.HomeProductSection;
import org.muybaby.shopserver.content.PublicContentChangedEvent;
import org.muybaby.shopserver.content.dto.AdminHomeCategoryOptionResponse;
import org.muybaby.shopserver.content.dto.AdminHomeCategoryRequest;
import org.muybaby.shopserver.content.dto.AdminHomeCategoryResponse;
import org.muybaby.shopserver.content.dto.AdminHomeProductOptionQuery;
import org.muybaby.shopserver.content.dto.AdminHomeProductOptionResponse;
import org.muybaby.shopserver.content.dto.AdminHomeProductRequest;
import org.muybaby.shopserver.content.dto.AdminHomeProductResponse;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HomeDecorationService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;
    private final ApplicationEventPublisher eventPublisher;

    public HomeDecorationService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
        this.eventPublisher = eventPublisher;
    }

    public List<AdminHomeCategoryResponse> categories() {
        return jdbcClient.sql("""
                        select i.id, i.category_id, c.name as category_name, c.status as category_status,
                               i.image_file_id, i.image_url, i.sort_order, i.status, i.created_at, i.updated_at
                        from home_category_item i
                        join product_category c on c.id = i.category_id
                        order by i.sort_order asc, i.id desc
                        """)
                .query((rs, rowNum) -> new AdminHomeCategoryResponse(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        rs.getString("category_name"),
                        rs.getString("category_status"),
                        rs.getLong("image_file_id"),
                        rs.getString("image_url"),
                        rs.getInt("sort_order"),
                        rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("updated_at", LocalDateTime.class)
                ))
                .list();
    }

    @Transactional
    public Long createCategory(AdminHomeCategoryRequest request) {
        HomeBannerStatus status = parseStatus(request.status());
        CategorySnapshot category = requireCategory(request.categoryId(), status == HomeBannerStatus.ENABLED);
        requireUniqueCategory(request.categoryId(), null);
        int sortOrder = normalizeSortOrder(request.sortOrder());
        String imageUrl = resolveImageUrl(request.imageFileId());

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into home_category_item
                                (category_id, image_file_id, image_url, sort_order, status)
                            values
                                (:categoryId, :imageFileId, :imageUrl, :sortOrder, :status)
                            """,
                    new MapSqlParameterSource()
                            .addValue("categoryId", request.categoryId())
                            .addValue("imageFileId", request.imageFileId())
                            .addValue("imageUrl", imageUrl)
                            .addValue("sortOrder", sortOrder)
                            .addValue("status", status.name()),
                    keyHolder,
                    new String[]{"id"});
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Long itemId = generatedId(keyHolder);
        syncCategoryUsage(itemId, category.name(), request.imageFileId(), imageUrl, sortOrder);
        publishHomeChanged();
        return itemId;
    }

    @Transactional
    public void updateCategory(Long itemId, AdminHomeCategoryRequest request) {
        requireCategoryItem(itemId);
        HomeBannerStatus status = parseStatus(request.status());
        CategorySnapshot category = requireCategory(request.categoryId(), status == HomeBannerStatus.ENABLED);
        requireUniqueCategory(request.categoryId(), itemId);
        int sortOrder = normalizeSortOrder(request.sortOrder());
        String imageUrl = resolveImageUrl(request.imageFileId());
        try {
            int updated = jdbcClient.sql("""
                            update home_category_item
                            set category_id = :categoryId,
                                image_file_id = :imageFileId,
                                image_url = :imageUrl,
                                sort_order = :sortOrder,
                                status = :status,
                                updated_at = current_timestamp
                            where id = :itemId
                            """)
                    .param("categoryId", request.categoryId())
                    .param("imageFileId", request.imageFileId())
                    .param("imageUrl", imageUrl)
                    .param("sortOrder", sortOrder)
                    .param("status", status.name())
                    .param("itemId", itemId)
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        syncCategoryUsage(itemId, category.name(), request.imageFileId(), imageUrl, sortOrder);
        publishHomeChanged();
    }

    @Transactional
    public void deleteCategory(Long itemId) {
        requireCategoryItem(itemId);
        jdbcClient.sql("delete from home_category_item where id = :itemId")
                .param("itemId", itemId)
                .update();
        storageUsageService.removeOwnerUsages(StorageUsageOwnerType.HOME_CATEGORY_ITEM, itemId);
        publishHomeChanged();
    }

    public List<AdminHomeProductResponse> products(HomeProductSection section) {
        return jdbcClient.sql("""
                        select i.id, i.section_type, i.spu_id, s.title as product_title,
                               s.subtitle as product_subtitle, s.status as product_status,
                               c.name as category_name, i.image_file_id, i.image_url,
                               s.main_image as product_image_url,
                               min(k.price_cent) as min_price_cent, max(k.price_cent) as max_price_cent,
                               i.sort_order, i.status, i.created_at, i.updated_at
                        from home_product_item i
                        join product_spu s on s.id = i.spu_id
                        join product_category c on c.id = s.category_id
                        left join product_sku k on k.spu_id = s.id
                          and k.status = 'ENABLED' and k.deleted_at is null
                        where i.section_type = :sectionType
                        group by i.id, i.section_type, i.spu_id, s.title, s.subtitle, s.status,
                                 c.name, i.image_file_id, i.image_url, s.main_image,
                                 i.sort_order, i.status, i.created_at, i.updated_at
                        order by i.sort_order asc, i.id desc
                        """)
                .param("sectionType", section.name())
                .query((rs, rowNum) -> {
                    String overrideImage = rs.getString("image_url");
                    String productImage = rs.getString("product_image_url");
                    return new AdminHomeProductResponse(
                            rs.getLong("id"),
                            rs.getString("section_type"),
                            rs.getLong("spu_id"),
                            rs.getString("product_title"),
                            rs.getString("product_subtitle"),
                            rs.getString("product_status"),
                            rs.getString("category_name"),
                            rs.getObject("image_file_id", Long.class),
                            overrideImage,
                            productImage,
                            StringUtils.hasText(overrideImage) ? overrideImage : productImage,
                            rs.getObject("min_price_cent", Long.class),
                            rs.getObject("max_price_cent", Long.class),
                            rs.getInt("sort_order"),
                            rs.getString("status"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("updated_at", LocalDateTime.class)
                    );
                })
                .list();
    }

    @Transactional
    public Long createProduct(HomeProductSection section, AdminHomeProductRequest request) {
        HomeBannerStatus status = parseStatus(request.status());
        ProductSnapshot product = requireProduct(request.spuId(), status == HomeBannerStatus.ENABLED);
        requireUniqueProduct(section, request.spuId(), null);
        int sortOrder = normalizeSortOrder(request.sortOrder());
        String imageUrl = request.imageFileId() == null ? "" : resolveImageUrl(request.imageFileId());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into home_product_item
                                (section_type, spu_id, image_file_id, image_url, sort_order, status)
                            values
                                (:sectionType, :spuId, :imageFileId, :imageUrl, :sortOrder, :status)
                            """,
                    new MapSqlParameterSource()
                            .addValue("sectionType", section.name())
                            .addValue("spuId", request.spuId())
                            .addValue("imageFileId", request.imageFileId())
                            .addValue("imageUrl", imageUrl)
                            .addValue("sortOrder", sortOrder)
                            .addValue("status", status.name()),
                    keyHolder,
                    new String[]{"id"});
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Long itemId = generatedId(keyHolder);
        syncProductUsage(itemId, product.title(), request.imageFileId(), imageUrl, sortOrder);
        publishHomeChanged();
        return itemId;
    }

    @Transactional
    public void updateProduct(Long itemId, HomeProductSection section, AdminHomeProductRequest request) {
        requireProductItem(itemId, section);
        HomeBannerStatus status = parseStatus(request.status());
        ProductSnapshot product = requireProduct(request.spuId(), status == HomeBannerStatus.ENABLED);
        requireUniqueProduct(section, request.spuId(), itemId);
        int sortOrder = normalizeSortOrder(request.sortOrder());
        String imageUrl = request.imageFileId() == null ? "" : resolveImageUrl(request.imageFileId());
        try {
            int updated = jdbcClient.sql("""
                            update home_product_item
                            set spu_id = :spuId,
                                image_file_id = :imageFileId,
                                image_url = :imageUrl,
                                sort_order = :sortOrder,
                                status = :status,
                                updated_at = current_timestamp
                            where id = :itemId and section_type = :sectionType
                            """)
                    .param("spuId", request.spuId())
                    .param("imageFileId", request.imageFileId())
                    .param("imageUrl", imageUrl)
                    .param("sortOrder", sortOrder)
                    .param("status", status.name())
                    .param("itemId", itemId)
                    .param("sectionType", section.name())
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        syncProductUsage(itemId, product.title(), request.imageFileId(), imageUrl, sortOrder);
        publishHomeChanged();
    }

    @Transactional
    public void deleteProduct(Long itemId, HomeProductSection section) {
        requireProductItem(itemId, section);
        jdbcClient.sql("delete from home_product_item where id = :itemId and section_type = :sectionType")
                .param("itemId", itemId)
                .param("sectionType", section.name())
                .update();
        storageUsageService.removeOwnerUsages(StorageUsageOwnerType.HOME_PRODUCT_ITEM, itemId);
        publishHomeChanged();
    }

    public List<AdminHomeCategoryOptionResponse> categoryOptions() {
        return jdbcClient.sql("""
                        select id, parent_id, name, icon
                        from product_category
                        where status = 'ENABLED'
                        order by parent_id asc, sort_order asc, id asc
                        """)
                .query((rs, rowNum) -> new AdminHomeCategoryOptionResponse(
                        rs.getLong("id"),
                        rs.getLong("parent_id"),
                        rs.getString("name"),
                        rs.getString("icon")
                ))
                .list();
    }

    public PageResult<AdminHomeProductOptionResponse> productOptions(AdminHomeProductOptionQuery query) {
        AdminHomeProductOptionQuery normalized = query == null
                ? new AdminHomeProductOptionQuery(null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        String keyword = StringUtils.hasText(normalized.keyword()) ? "%" + normalized.keyword().trim() + "%" : null;
        Long total = jdbcClient.sql("""
                        select count(*)
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        where s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                          and (:keyword is null or s.title like :keyword)
                        """)
                .param("keyword", keyword)
                .query(Long.class)
                .single();
        List<AdminHomeProductOptionResponse> records = jdbcClient.sql("""
                        select s.id, s.category_id, c.name as category_name, s.title, s.subtitle, s.main_image,
                               min(k.price_cent) as min_price_cent, max(k.price_cent) as max_price_cent
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        left join product_sku k on k.spu_id = s.id
                          and k.status = 'ENABLED' and k.deleted_at is null
                        where s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                          and (:keyword is null or s.title like :keyword)
                        group by s.id, s.category_id, c.name, s.title, s.subtitle, s.main_image, s.sort_order
                        order by s.sort_order asc, s.id desc
                        limit :limit offset :offset
                        """)
                .param("keyword", keyword)
                .param("limit", size)
                .param("offset", offset)
                .query((rs, rowNum) -> new AdminHomeProductOptionResponse(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        rs.getString("category_name"),
                        rs.getString("title"),
                        rs.getString("subtitle"),
                        rs.getString("main_image"),
                        rs.getObject("min_price_cent", Long.class),
                        rs.getObject("max_price_cent", Long.class)
                ))
                .list();
        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    private CategorySnapshot requireCategory(Long categoryId, boolean requireEnabled) {
        CategorySnapshot category = jdbcClient.sql("""
                        select id, name, status
                        from product_category
                        where id = :categoryId
                        for update
                        """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> new CategorySnapshot(
                        rs.getLong("id"), rs.getString("name"), rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE));
        if (requireEnabled && !HomeBannerStatus.ENABLED.name().equals(category.status())) {
            throw new BusinessException(ErrorCode.PRODUCT_CATEGORY_UNAVAILABLE);
        }
        return category;
    }

    private ProductSnapshot requireProduct(Long spuId, boolean requireOnSale) {
        ProductSnapshot product = jdbcClient.sql("""
                        select s.id, s.title, s.status, s.deleted_at, s.purged_at, c.status as category_status
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        where s.id = :spuId
                        for update
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> new ProductSnapshot(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getObject("deleted_at", LocalDateTime.class),
                        rs.getObject("purged_at", LocalDateTime.class),
                        rs.getString("category_status")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
        if (product.purgedAt() != null || product.deletedAt() != null
                || (requireOnSale && (!"ON_SALE".equals(product.status())
                || !HomeBannerStatus.ENABLED.name().equals(product.categoryStatus())))) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        return product;
    }

    private void requireCategoryItem(Long itemId) {
        boolean exists = jdbcClient.sql("select id from home_category_item where id = :itemId for update")
                .param("itemId", itemId)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void requireProductItem(Long itemId, HomeProductSection section) {
        boolean exists = jdbcClient.sql("""
                        select id from home_product_item
                        where id = :itemId and section_type = :sectionType
                        for update
                        """)
                .param("itemId", itemId)
                .param("sectionType", section.name())
                .query(Long.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void requireUniqueCategory(Long categoryId, Long ignoredItemId) {
        Integer count = jdbcClient.sql("""
                        select count(*) from home_category_item
                        where category_id = :categoryId
                          and (:ignoredItemId is null or id <> :ignoredItemId)
                        """)
                .param("categoryId", categoryId)
                .param("ignoredItemId", ignoredItemId)
                .query(Integer.class)
                .single();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void requireUniqueProduct(HomeProductSection section, Long spuId, Long ignoredItemId) {
        Integer count = jdbcClient.sql("""
                        select count(*) from home_product_item
                        where section_type = :sectionType
                          and spu_id = :spuId
                          and (:ignoredItemId is null or id <> :ignoredItemId)
                        """)
                .param("sectionType", section.name())
                .param("spuId", spuId)
                .param("ignoredItemId", ignoredItemId)
                .query(Integer.class)
                .single();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String resolveImageUrl(Long imageFileId) {
        if (imageFileId == null) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.IMAGE);
        return jdbcClient.sql("""
                        select public_url from storage_asset
                        where id = :imageFileId
                          and scope = 'LIBRARY'
                          and media_kind = 'IMAGE'
                          and visibility = 'PUBLIC'
                          and status = 'ACTIVE'
                        """)
                .param("imageFileId", imageFileId)
                .query(String.class)
                .optional()
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private void syncCategoryUsage(Long itemId, String label, Long imageFileId, String imageUrl, int sortOrder) {
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.HOME_CATEGORY_ITEM,
                itemId,
                label,
                List.of(new StorageUsageService.UsageAssignment(
                        imageFileId, StorageFileUsageType.HOME_CATEGORY_IMAGE, imageUrl, sortOrder, false
                ))
        );
    }

    private void syncProductUsage(Long itemId, String label, Long imageFileId, String imageUrl, int sortOrder) {
        List<StorageUsageService.UsageAssignment> usages = imageFileId == null
                ? List.of()
                : List.of(new StorageUsageService.UsageAssignment(
                imageFileId, StorageFileUsageType.HOME_PRODUCT_IMAGE, imageUrl, sortOrder, false
        ));
        storageUsageService.replaceOwnerUsages(StorageUsageOwnerType.HOME_PRODUCT_ITEM, itemId, label, usages);
    }

    private HomeBannerStatus parseStatus(String value) {
        try {
            return HomeBannerStatus.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private int normalizeSortOrder(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private Long generatedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create home decoration item");
        }
        return key.longValue();
    }

    private void publishHomeChanged() {
        eventPublisher.publishEvent(PublicContentChangedEvent.home());
    }

    private record CategorySnapshot(Long id, String name, String status) {
    }

    private record ProductSnapshot(
            Long id,
            String title,
            String status,
            LocalDateTime deletedAt,
            LocalDateTime purgedAt,
            String categoryStatus
    ) {
    }
}
