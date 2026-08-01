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
import org.muybaby.shopserver.content.dto.AdminHomeAutoFillResponse;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<HomeProductRow> rows = jdbcClient.sql("""
                        select i.id, i.section_type, i.spu_id, s.title as product_title,
                               s.subtitle as product_subtitle, s.status as product_status,
                               c.name as category_name, i.image_file_id, i.image_url,
                               s.main_image as product_image_url,
                               s.display_badge_text, s.display_badge_tone,
                               min(k.price_cent) as min_price_cent, max(k.price_cent) as max_price_cent,
                               i.sort_order, i.status,
                               i.created_at, i.updated_at
                        from home_product_item i
                        join product_spu s on s.id = i.spu_id
                        join product_category c on c.id = s.category_id
                        left join product_sku k on k.spu_id = s.id
                          and k.status = 'ENABLED' and k.deleted_at is null
                        where i.section_type = :sectionType
                        group by i.id, i.section_type, i.spu_id, s.title, s.subtitle, s.status,
                                 c.name, i.image_file_id, i.image_url, s.main_image,
                                 s.display_badge_text, s.display_badge_tone, i.sort_order, i.status,
                                 i.created_at, i.updated_at
                        order by i.sort_order asc, i.id desc
                        """)
                .param("sectionType", section.name())
                .query((rs, rowNum) -> new HomeProductRow(
                            rs.getLong("id"),
                            rs.getString("section_type"),
                            rs.getLong("spu_id"),
                            rs.getString("product_title"),
                            rs.getString("product_subtitle"),
                            rs.getString("product_status"),
                            rs.getString("category_name"),
                            rs.getObject("image_file_id", Long.class),
                            rs.getString("image_url"),
                            rs.getString("product_image_url"),
                            rs.getObject("min_price_cent", Long.class),
                            rs.getObject("max_price_cent", Long.class),
                            rs.getInt("sort_order"),
                            rs.getString("status"),
                            rs.getString("display_badge_text"),
                            rs.getString("display_badge_tone"),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("updated_at", LocalDateTime.class)
                    ))
                .list();
        List<AdminHomeProductResponse> result = new ArrayList<>();
        for (HomeProductRow row : rows) {
            String displayImage = StringUtils.hasText(row.imageUrl()) ? row.imageUrl() : row.productImageUrl();
            result.add(new AdminHomeProductResponse(
                    row.id(), row.sectionType(), row.spuId(), row.productTitle(), row.productSubtitle(),
                    row.productStatus(), row.categoryName(), row.imageFileId(), row.imageUrl(),
                    row.productImageUrl(), displayImage, row.minPriceCent(), row.maxPriceCent(),
                    row.sortOrder(), row.status(), row.displayBadgeText(), row.displayBadgeTone(),
                    row.createdAt(), row.updatedAt()
            ));
        }
        return result;
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
    public AdminHomeAutoFillResponse autoFillProducts(HomeProductSection section, Integer targetCount) {
        if (targetCount == null || targetCount < 1 || targetCount > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        jdbcClient.sql("select section_type from home_product_fill_guard where section_type = :sectionType for update")
                .param("sectionType", section.name())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Home product fill guard is unavailable"));

        Integer existing = jdbcClient.sql("""
                        select count(*)
                        from home_product_item i
                        join product_spu s on s.id = i.spu_id
                        join product_category c on c.id = s.category_id
                        where i.section_type = :sectionType
                          and i.status = 'ENABLED'
                          and s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                        """)
                .param("sectionType", section.name())
                .query(Integer.class)
                .single();
        int existingCount = existing == null ? 0 : existing;
        int needed = Math.max(0, targetCount - existingCount);
        if (needed == 0) {
            return new AdminHomeAutoFillResponse(
                    targetCount, existingCount, 0, existingCount, false, List.of()
            );
        }

        List<AutoFillCandidate> candidates = autoFillCandidates(section);
        List<AutoFillCandidate> selected = selectAutoFillCandidates(section, candidates, needed);
        Integer maximumSortOrder = jdbcClient.sql("""
                        select coalesce(max(sort_order), -1)
                        from home_product_item
                        where section_type = :sectionType
                        """)
                .param("sectionType", section.name())
                .query(Integer.class)
                .single();
        int nextSortOrder = (maximumSortOrder == null ? -1 : maximumSortOrder) + 1;
        List<Long> addedSpuIds = new ArrayList<>();
        for (AutoFillCandidate candidate : selected) {
            jdbcClient.sql("""
                            insert into home_product_item
                                (section_type, spu_id, image_file_id, image_url, sort_order, status)
                            values
                                (:sectionType, :spuId, null, '', :sortOrder, 'ENABLED')
                            """)
                    .param("sectionType", section.name())
                    .param("spuId", candidate.spuId())
                    .param("sortOrder", nextSortOrder++)
                    .update();
            addedSpuIds.add(candidate.spuId());
        }
        if (!addedSpuIds.isEmpty()) {
            publishHomeChanged();
        }
        int finalCount = existingCount + addedSpuIds.size();
        return new AdminHomeAutoFillResponse(
                targetCount,
                existingCount,
                addedSpuIds.size(),
                finalCount,
                finalCount < targetCount,
                addedSpuIds
        );
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
        String keywordText = StringUtils.hasText(normalized.keyword()) ? normalized.keyword().trim() : null;
        String keyword = keywordText == null ? null : "%" + keywordText + "%";
        Long keywordId = parsePositiveLong(keywordText);
        Long total = jdbcClient.sql("""
                        select count(*)
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        where s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                          and (
                              :keyword is null
                              or s.title like :keyword
                              or (:keywordId is not null and s.id = :keywordId)
                          )
                        """)
                .param("keyword", keyword)
                .param("keywordId", keywordId)
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
                          and (
                              :keyword is null
                              or s.title like :keyword
                              or (:keywordId is not null and s.id = :keywordId)
                          )
                        group by s.id, s.category_id, c.name, s.title, s.subtitle, s.main_image, s.sort_order
                        order by s.sort_order asc, s.id desc
                        limit :limit offset :offset
                        """)
                .param("keyword", keyword)
                .param("keywordId", keywordId)
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

    private List<AutoFillCandidate> autoFillCandidates(HomeProductSection section) {
        LocalDateTime salesSince = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(30);
        return jdbcClient.sql("""
                        select s.id as spu_id, s.category_id, s.sort_order,
                               stock.total_stock,
                               coalesce(sales.recent_sales, 0) as recent_sales,
                               coalesce(sales.total_sales, 0) as total_sales
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        join (
                            select spu_id, sum(stock_available) as total_stock
                            from product_sku
                            where status = 'ENABLED'
                              and deleted_at is null
                              and stock_available > 0
                              and price_cent > 0
                            group by spu_id
                        ) stock on stock.spu_id = s.id
                        left join (
                            select oi.spu_id,
                                   sum(case when o.paid_at >= :salesSince then oi.quantity else 0 end) as recent_sales,
                                   sum(oi.quantity) as total_sales
                            from order_item oi
                            join shop_order o on o.id = oi.order_id
                            where o.paid_at is not null
                            group by oi.spu_id
                        ) sales on sales.spu_id = s.id
                        where s.status = 'ON_SALE'
                          and s.deleted_at is null
                          and s.purged_at is null
                          and c.status = 'ENABLED'
                          and s.main_image <> ''
                          and not exists (
                              select 1 from home_product_item i
                              where i.section_type = :sectionType and i.spu_id = s.id
                          )
                        order by s.id
                        limit 500
                        """)
                .param("salesSince", salesSince)
                .param("sectionType", section.name())
                .query((rs, rowNum) -> {
                    return new AutoFillCandidate(
                            rs.getLong("spu_id"),
                            rs.getLong("category_id"),
                            rs.getInt("sort_order"),
                            rs.getLong("total_stock"),
                            rs.getLong("recent_sales"),
                            rs.getLong("total_sales")
                    );
                })
                .list();
    }

    private List<AutoFillCandidate> selectAutoFillCandidates(
            HomeProductSection section,
            List<AutoFillCandidate> candidates,
            int needed
    ) {
        Comparator<AutoFillCandidate> comparator = section == HomeProductSection.HOT
                ? Comparator.comparingLong(AutoFillCandidate::recentSales).reversed()
                .thenComparing(Comparator.comparingLong(AutoFillCandidate::totalSales).reversed())
                .thenComparing(Comparator.comparingLong(AutoFillCandidate::totalStock).reversed())
                .thenComparingInt(AutoFillCandidate::sortOrder)
                .thenComparing(Comparator.comparingLong(AutoFillCandidate::spuId).reversed())
                : Comparator.comparingLong(AutoFillCandidate::recentSales).reversed()
                .thenComparing(Comparator.comparingLong(AutoFillCandidate::totalSales).reversed())
                .thenComparing(Comparator.comparingLong(AutoFillCandidate::totalStock).reversed())
                .thenComparingInt(AutoFillCandidate::sortOrder)
                .thenComparing(Comparator.comparingLong(AutoFillCandidate::spuId).reversed());
        List<AutoFillCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(comparator);
        if (section == HomeProductSection.HOT) {
            return ordered.stream().limit(needed).toList();
        }

        Map<Long, ArrayDeque<AutoFillCandidate>> byCategory = new LinkedHashMap<>();
        for (AutoFillCandidate candidate : ordered) {
            byCategory.computeIfAbsent(candidate.categoryId(), ignored -> new ArrayDeque<>()).add(candidate);
        }
        List<AutoFillCandidate> diversified = new ArrayList<>();
        while (diversified.size() < needed && !byCategory.isEmpty()) {
            List<Long> emptyCategories = new ArrayList<>();
            for (Map.Entry<Long, ArrayDeque<AutoFillCandidate>> entry : byCategory.entrySet()) {
                AutoFillCandidate candidate = entry.getValue().pollFirst();
                if (candidate != null && diversified.size() < needed) {
                    diversified.add(candidate);
                }
                if (entry.getValue().isEmpty()) {
                    emptyCategories.add(entry.getKey());
                }
            }
            emptyCategories.forEach(byCategory::remove);
        }
        return diversified;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private HomeBannerStatus parseStatus(String value) {
        try {
            return HomeBannerStatus.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Long parsePositiveLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
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

    private record HomeProductRow(
            Long id,
            String sectionType,
            Long spuId,
            String productTitle,
            String productSubtitle,
            String productStatus,
            String categoryName,
            Long imageFileId,
            String imageUrl,
            String productImageUrl,
            Long minPriceCent,
            Long maxPriceCent,
            Integer sortOrder,
            String status,
            String displayBadgeText,
            String displayBadgeTone,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record AutoFillCandidate(
            Long spuId,
            Long categoryId,
            Integer sortOrder,
            Long totalStock,
            Long recentSales,
            Long totalSales
    ) {
    }
}
