package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceQueryRequest;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ProductGuaranteeServiceReadMapper {

    private final JdbcClient jdbcClient;

    public ProductGuaranteeServiceReadMapper(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<AdminGuaranteeServiceResponse> page(AdminGuaranteeServiceQueryRequest query) {
        AdminGuaranteeServiceQueryRequest normalized = query == null
                ? new AdminGuaranteeServiceQueryRequest(null, null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        String name = blankToNull(normalized.name());

        Long total = jdbcClient.sql("""
                        select count(*)
                        from product_guarantee_service
                        where deleted_at is null
                          and (:name is null or terms_name like :namePattern)
                          and (:visible is null or visible = :visible)
                        """)
                .param("name", name)
                .param("namePattern", likePattern(name))
                .param("visible", normalized.visible())
                .query(Long.class)
                .single();

        List<AdminGuaranteeServiceResponse> records = jdbcClient.sql("""
                        select id, terms_name, content_description, icon, icon_file_id,
                               sort_order, visible, created_at, updated_at
                        from product_guarantee_service
                        where deleted_at is null
                          and (:name is null or terms_name like :namePattern)
                          and (:visible is null or visible = :visible)
                        order by sort_order asc, id desc
                        limit :limit offset :offset
                        """)
                .param("name", name)
                .param("namePattern", likePattern(name))
                .param("visible", normalized.visible())
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapResponse)
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public Optional<AdminGuaranteeServiceResponse> findById(Long serviceId) {
        return jdbcClient.sql("""
                        select id, terms_name, content_description, icon, icon_file_id,
                               sort_order, visible, created_at, updated_at
                        from product_guarantee_service
                        where id = :serviceId
                          and deleted_at is null
                        """)
                .param("serviceId", serviceId)
                .query(this::mapResponse)
                .optional();
    }

    private AdminGuaranteeServiceResponse mapResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminGuaranteeServiceResponse(
                rs.getLong("id"),
                rs.getString("terms_name"),
                rs.getString("content_description"),
                rs.getString("icon"),
                rs.getObject("icon_file_id", Long.class),
                rs.getInt("sort_order"),
                rs.getBoolean("visible"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String likePattern(String value) {
        return value == null ? "%" : "%" + value + "%";
    }
}
