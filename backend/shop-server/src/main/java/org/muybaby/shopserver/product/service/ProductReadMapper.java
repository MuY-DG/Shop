package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryResponse;
import org.muybaby.shopserver.product.dto.AdminSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpuListItemResponse;
import org.muybaby.shopserver.product.dto.AdminSpuQueryRequest;
import org.muybaby.shopserver.product.dto.AppSkuResponse;
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
                        SELECT id, parent_id, name, icon, sort_order, status
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
        AdminSpuQueryRequest normalizedQuery = query == null ? new AdminSpuQueryRequest(null, null, null, null, null) : query;
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;

        String titleLike = StringUtils.hasText(normalizedQuery.title()) ? "%" + normalizedQuery.title().trim() + "%" : null;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from product_spu s
                        where (:categoryId is null or s.category_id = :categoryId)
                          and (:status is null or s.status = :status)
                          and (:titleLike is null or s.title like :titleLike)
                        """)
                .param("categoryId", normalizedQuery.categoryId())
                .param("status", normalizedQuery.status())
                .param("titleLike", titleLike)
                .query(Long.class)
                .single();

        List<AdminSpuListItemResponse> records = jdbcClient.sql("""
                        select s.id, s.category_id, c.name as category_name, s.title, s.subtitle, s.main_image,
                               s.status, s.sort_order, s.created_at, s.updated_at,
                               min(k.price_cent) as min_price_cent,
                               max(k.price_cent) as max_price_cent,
                               coalesce(sum(k.stock_available), 0) as total_stock,
                               count(k.id) as sku_count
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        left join product_sku k on k.spu_id = s.id
                        where (:categoryId is null or s.category_id = :categoryId)
                          and (:status is null or s.status = :status)
                          and (:titleLike is null or s.title like :titleLike)
                        group by s.id, s.category_id, c.name, s.title, s.subtitle, s.main_image,
                                 s.status, s.sort_order, s.created_at, s.updated_at
                        order by s.sort_order asc, s.id desc
                        limit :limit offset :offset
                        """)
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
                               s.selling_points, s.detail_html, s.sort_order, s.status, s.created_at, s.updated_at
                        from product_spu s
                        join product_category c on c.id = s.category_id
                        where s.id = :spuId
                        """)
                .param("spuId", spuId)
                .query(this::mapSpuDetailRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));

        List<ProductImageResponse> images = jdbcClient.sql("""
                        select id, url, sort_order
                        from product_spu_image
                        where spu_id = :spuId
                        order by sort_order asc, id asc
                        """)
                .param("spuId", spuId)
                .query(this::mapProductImage)
                .list();

        List<AppSkuResponse> skus = jdbcClient.sql("""
                        select id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               stock_available, weight_gram, image, status
                        from product_sku
                        where spu_id = :spuId
                        order by sort_order asc, id asc
                        """)
                .param("spuId", spuId)
                .query(this::mapAppSku)
                .list();

        return new AdminSpuDetailResponse(
                spu.id(),
                spu.categoryId(),
                spu.categoryName(),
                spu.title(),
                spu.subtitle(),
                spu.mainImage(),
                spu.sellingPoints(),
                spu.detailHtml(),
                spu.sortOrder(),
                spu.status(),
                images,
                skus,
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
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
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
                rs.getInt("weight_gram"),
                rs.getString("image"),
                rs.getString("status")
        );
    }

    private record CategoryRow(
            Long id,
            Long parentId,
            String name,
            String icon,
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
                    sortOrder,
                    status,
                    children.stream()
                            .map(MutableCategory::toResponse)
                            .toList()
            );
        }
    }
}
