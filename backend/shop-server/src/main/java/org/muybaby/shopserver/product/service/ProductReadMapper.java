package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.product.dto.AdminCategoryResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    private record CategoryRow(
            Long id,
            Long parentId,
            String name,
            String icon,
            Integer sortOrder,
            String status
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
