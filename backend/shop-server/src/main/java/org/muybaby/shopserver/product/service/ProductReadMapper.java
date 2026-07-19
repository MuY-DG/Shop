package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryResponse;
import org.muybaby.shopserver.product.dto.AdminSkuResponse;
import org.muybaby.shopserver.product.dto.AdminSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpuListItemResponse;
import org.muybaby.shopserver.product.dto.AdminSpuSpecGroupResponse;
import org.muybaby.shopserver.product.dto.AdminSpuSpecValueResponse;
import org.muybaby.shopserver.product.dto.AdminSpuQueryRequest;
import org.muybaby.shopserver.product.dto.ProductImageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductReadMapper {

    private final JdbcClient jdbcClient;

    public ProductReadMapper(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AdminCategoryResponse> adminCategoryTree() {
        List<CategoryRow> categoryRows = jdbcClient.sql("""
                        SELECT id, parent_id, name, icon, icon_file_id, sort_order, status
                        FROM product_category
                        ORDER BY parent_id, sort_order, id
                        """)
                .query(this::mapCategoryRow)
                .list();

        Map<Long, MutableCategory> categoriesById = new LinkedHashMap<>();
        for (CategoryRow categoryRow : categoryRows) {
            categoriesById.put(categoryRow.id(), MutableCategory.from(categoryRow));
        }

        List<MutableCategory> roots = new ArrayList<>();
        for (CategoryRow categoryRow : categoryRows) {
            MutableCategory category = categoriesById.get(categoryRow.id());
            if (categoryRow.parentId() == 0L) {
                roots.add(category);
                continue;
            }
            MutableCategory parent = categoriesById.get(categoryRow.parentId());
            if (parent != null) {
                parent.children().add(category);
            }
        }

        return roots.stream()
                .map(MutableCategory::toResponse)
                .toList();
    }

    public PageResult<AdminSpuListItemResponse> adminSpuPage(AdminSpuQueryRequest query) {
        AdminSpuQueryRequest normalizedQuery = query == null
                ? new AdminSpuQueryRequest(null, null, null, false, null, null)
                : query;
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;
        boolean recycled = Boolean.TRUE.equals(normalizedQuery.recycled());
        String recyclePredicate = recycled
                ? "s.deleted_at is not null and s.purged_at is null"
                : "s.deleted_at is null and s.purged_at is null";
        String orderBy = recycled
                ? "s.deleted_at desc, s.id desc"
                : "s.sort_order asc, s.id desc";

        String titleLike = StringUtils.hasText(normalizedQuery.title()) ? "%" + normalizedQuery.title().trim() + "%" : null;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from product_spu s
                        where %s
                          and (:categoryId is null or s.category_id = :categoryId)
                          and (:status is null or s.status = :status)
                          and (:titleLike is null or s.title like :titleLike)
                        """.formatted(recyclePredicate))
                .param("categoryId", normalizedQuery.categoryId())
                .param("status", normalizedQuery.status())
                .param("titleLike", titleLike)
                .query(Long.class)
                .single();

        List<AdminSpuListItemResponse> records = jdbcClient.sql("""
                        select s.id, s.category_id, c.name as category_name, s.title, s.subtitle, s.main_image,
                               s.status, s.sort_order, s.virtual_sales, s.created_at, s.updated_at, s.deleted_at,
                               min(k.price_cent) as min_price_cent,
                               max(k.price_cent) as max_price_cent,
                               coalesce(sum(k.stock_available), 0) as total_stock,
                               count(k.id) as sku_count,
                               coalesce(sales.actual_sales, 0) as actual_sales
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        left join product_sku k on k.spu_id = s.id and k.deleted_at is null
                        left join (
                            select oi.spu_id, sum(oi.quantity) as actual_sales
                            from order_item oi
                            join shop_order o on o.id = oi.order_id
                            where o.paid_at is not null
                            group by oi.spu_id
                        ) sales on sales.spu_id = s.id
                        where %s
                          and (:categoryId is null or s.category_id = :categoryId)
                          and (:status is null or s.status = :status)
                          and (:titleLike is null or s.title like :titleLike)
                        group by s.id, s.category_id, c.name, s.title, s.subtitle, s.main_image,
                                 s.status, s.sort_order, s.virtual_sales, sales.actual_sales,
                                 s.created_at, s.updated_at, s.deleted_at
                        order by %s
                        limit :limit offset :offset
                        """.formatted(recyclePredicate, orderBy))
                .param("categoryId", normalizedQuery.categoryId())
                .param("status", normalizedQuery.status())
                .param("titleLike", titleLike)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAdminSpuListItem)
                .list();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    public AdminSpuDetailResponse adminSpuDetail(Long spuId) {
        SpuDetailRow spu = jdbcClient.sql("""
                        select s.id, s.category_id, c.name as category_name, s.title, s.subtitle, s.main_image,
                               s.main_image_file_id, s.main_video, s.main_video_file_id, s.spec_type,
                               s.freight_template_id, s.virtual_sales,
                               s.selling_points, s.detail_html, s.sort_order, s.status, s.created_at, s.updated_at
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        where s.id = :spuId and s.deleted_at is null
                        """)
                .param("spuId", spuId)
                .query(this::mapSpuDetailRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));

        List<ProductImageResponse> images = jdbcClient.sql("""
                        select id, url, file_id, sort_order
                        from product_spu_image
                        where spu_id = :spuId
                        order by sort_order asc, id asc
                        """)
                .param("spuId", spuId)
                .query(this::mapProductImage)
                .list();

        List<AdminSkuResponse> skus = jdbcClient.sql("""
                        select id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               cost_price_cent, stock_available, low_stock_threshold, weight_gram, volume_cubic_meter,
                               image, image_file_id, status, is_default, combination_key, sort_order
                        from product_sku
                        where spu_id = :spuId and deleted_at is null
                        order by sort_order asc, id asc
                        """)
                .param("spuId", spuId)
                .query(this::mapAdminSku)
                .list();
        skus = skus.stream()
                .map(sku -> new AdminSkuResponse(
                        sku.id(), sku.skuCode(), sku.specJson(), sku.specText(), sku.priceCent(),
                        sku.originalPriceCent(), sku.costPriceCent(), sku.stockAvailable(), sku.lowStockThreshold(), sku.weightGram(),
                        sku.volumeCubicMeter(), sku.image(), sku.imageFileId(), sku.status(),
                        sku.defaultSelected(), sku.combinationKey(), findSkuSpecValueKeys(sku.id()), sku.sortOrder()
                ))
                .toList();

        List<AdminSpuSpecGroupResponse> specGroups = jdbcClient.sql("""
                        select id, group_key, name, image_enabled, sort_order
                        from product_spu_spec_group
                        where spu_id = :spuId and deleted_at is null
                        order by sort_order, id
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> {
                    Long groupId = rs.getLong("id");
                    List<AdminSpuSpecValueResponse> values = jdbcClient.sql("""
                                    select id, value_key, value_name, image, image_file_id, sort_order
                                    from product_spu_spec_value
                                    where group_id = :groupId and deleted_at is null
                                    order by sort_order, id
                                    """)
                            .param("groupId", groupId)
                            .query((valueRs, valueRowNum) -> new AdminSpuSpecValueResponse(
                                    valueRs.getLong("id"),
                                    valueRs.getString("value_key"),
                                    valueRs.getString("value_name"),
                                    valueRs.getString("image"),
                                    valueRs.getObject("image_file_id", Long.class),
                                    valueRs.getInt("sort_order")
                            ))
                            .list();
                    return new AdminSpuSpecGroupResponse(
                            groupId,
                            rs.getString("group_key"),
                            rs.getString("name"),
                            rs.getBoolean("image_enabled"),
                            rs.getInt("sort_order"),
                            values
                    );
                })
                .list();
        List<String> tags = jdbcClient.sql("""
                        select tag_code from product_spu_tag
                        where spu_id = :spuId order by tag_code
                        """)
                .param("spuId", spuId)
                .query(String.class)
                .list();
        List<Long> guaranteeServiceIds = jdbcClient.sql("""
                        select gs.service_id
                        from product_spu_guarantee_service gs
                        join product_guarantee_service s on s.id = gs.service_id and s.deleted_at is null
                        where gs.spu_id = :spuId
                        order by gs.sort_order, gs.service_id
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list();
        List<Long> couponTemplateIds = jdbcClient.sql("""
                        select coupon_template_id from product_spu_coupon
                        where spu_id = :spuId order by coupon_template_id
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .list();

        return new AdminSpuDetailResponse(
                spu.id(),
                spu.categoryId(),
                spu.categoryName(),
                spu.title(),
                spu.subtitle(),
                spu.mainImage(),
                spu.mainImageFileId(),
                spu.mainVideo(),
                spu.mainVideoFileId(),
                spu.specType(),
                spu.freightTemplateId(),
                spu.virtualSales(),
                spu.sellingPoints(),
                spu.detailHtml(),
                spu.sortOrder(),
                spu.status(),
                images,
                skus,
                specGroups,
                tags,
                guaranteeServiceIds,
                couponTemplateIds,
                spu.createdAt(),
                spu.updatedAt()
        );
    }

    private CategoryRow mapCategoryRow(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryRow(
                rs.getLong("id"),
                rs.getLong("parent_id"),
                rs.getString("name"),
                rs.getString("icon"),
                rs.getObject("icon_file_id", Long.class),
                rs.getInt("sort_order"),
                rs.getString("status")
        );
    }

    private AdminSpuListItemResponse mapAdminSpuListItem(ResultSet rs, int rowNum) throws SQLException {
        return new AdminSpuListItemResponse(
                rs.getLong("id"),
                rs.getLong("category_id"),
                rs.getString("category_name"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("main_image"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("min_price_cent", Long.class),
                rs.getObject("max_price_cent", Long.class),
                rs.getInt("total_stock"),
                rs.getInt("sku_count"),
                rs.getLong("actual_sales"),
                rs.getLong("virtual_sales"),
                rs.getLong("actual_sales") + rs.getLong("virtual_sales"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class)
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
                rs.getString("main_video"),
                rs.getObject("main_video_file_id", Long.class),
                rs.getString("spec_type"),
                rs.getLong("freight_template_id"),
                rs.getLong("virtual_sales"),
                rs.getString("selling_points"),
                rs.getString("detail_html"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
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

    private AdminSkuResponse mapAdminSku(ResultSet rs, int rowNum) throws SQLException {
        return new AdminSkuResponse(
                rs.getLong("id"),
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
                List.of(),
                rs.getInt("sort_order")
        );
    }

    private List<String> findSkuSpecValueKeys(Long skuId) {
        return jdbcClient.sql("""
                        select v.value_key
                        from product_sku_spec_value sv
                        join product_spu_spec_value v on v.id = sv.spec_value_id
                        join product_spu_spec_group g on g.id = v.group_id
                        where sv.sku_id = :skuId
                        order by g.sort_order, g.id, v.sort_order, v.id
                        """)
                .param("skuId", skuId)
                .query(String.class)
                .list();
    }

    private record CategoryRow(
            Long id,
            Long parentId,
            String name,
            String icon,
            Long iconFileId,
            Integer sortOrder,
            String status
    ) {
    }

    private record SpuDetailRow(
            Long id,
            Long categoryId,
            String categoryName,
            String title,
            String subtitle,
            String mainImage,
            Long mainImageFileId,
            String mainVideo,
            Long mainVideoFileId,
            String specType,
            Long freightTemplateId,
            Long virtualSales,
            String sellingPoints,
            String detailHtml,
            Integer sortOrder,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record MutableCategory(
            Long id,
            Long parentId,
            String name,
            String icon,
            Long iconFileId,
            Integer sortOrder,
            String status,
            List<MutableCategory> children
    ) {

        private static MutableCategory from(CategoryRow categoryRow) {
            return new MutableCategory(
                    categoryRow.id(),
                    categoryRow.parentId(),
                    categoryRow.name(),
                    categoryRow.icon(),
                    categoryRow.iconFileId(),
                    categoryRow.sortOrder(),
                    categoryRow.status(),
                    new ArrayList<>()
            );
        }

        private AdminCategoryResponse toResponse() {
            return new AdminCategoryResponse(
                    id,
                    parentId,
                    name,
                    icon,
                    iconFileId,
                    sortOrder,
                    status,
                    children.stream()
                            .map(MutableCategory::toResponse)
                            .toList()
            );
        }
    }
}
