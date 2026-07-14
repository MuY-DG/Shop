package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.product.dto.AdminSpecTemplateDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateGroupResponse;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateSummaryResponse;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateValueResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ProductSpecTemplateReadMapper {

    private final JdbcClient jdbcClient;

    public ProductSpecTemplateReadMapper(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AdminSpecTemplateSummaryResponse> list() {
        return jdbcClient.sql("""
                        select t.id,
                               t.name,
                               (select count(*)
                                from product_spec_template_group g
                                where g.template_id = t.id) as group_count,
                               (select count(*)
                                from product_spec_template_group g
                                join product_spec_template_value v on v.group_id = g.id
                                where g.template_id = t.id) as value_count,
                               t.created_at,
                               t.updated_at
                        from product_spec_template t
                        order by t.updated_at desc, t.id desc
                        """)
                .query(this::mapSummary)
                .list();
    }

    public Optional<AdminSpecTemplateDetailResponse> findDetail(Long templateId) {
        Optional<TemplateRow> template = jdbcClient.sql("""
                        select id, name, created_at, updated_at
                        from product_spec_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query(this::mapTemplateRow)
                .optional();
        if (template.isEmpty()) {
            return Optional.empty();
        }

        List<GroupRow> groupRows = jdbcClient.sql("""
                        select id, group_key, name, image_enabled, sort_order
                        from product_spec_template_group
                        where template_id = :templateId
                        order by sort_order asc, id asc
                        """)
                .param("templateId", templateId)
                .query(this::mapGroupRow)
                .list();
        List<AdminSpecTemplateGroupResponse> groups = new ArrayList<>();
        for (GroupRow group : groupRows) {
            List<AdminSpecTemplateValueResponse> values = jdbcClient.sql("""
                            select id, value_key, value_name, sort_order
                            from product_spec_template_value
                            where group_id = :groupId
                            order by sort_order asc, id asc
                            """)
                    .param("groupId", group.id())
                    .query(this::mapValue)
                    .list();
            groups.add(new AdminSpecTemplateGroupResponse(
                    group.id(),
                    group.groupKey(),
                    group.name(),
                    group.imageEnabled(),
                    group.sortOrder(),
                    values
            ));
        }
        TemplateRow row = template.get();
        return Optional.of(new AdminSpecTemplateDetailResponse(
                row.id(),
                row.name(),
                List.copyOf(groups),
                row.createdAt(),
                row.updatedAt()
        ));
    }

    private GroupRow mapGroupRow(ResultSet rs, int rowNum) throws SQLException {
        return new GroupRow(
                rs.getLong("id"),
                rs.getString("group_key"),
                rs.getString("name"),
                rs.getBoolean("image_enabled"),
                rs.getInt("sort_order")
        );
    }

    private AdminSpecTemplateSummaryResponse mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new AdminSpecTemplateSummaryResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("group_count"),
                rs.getInt("value_count"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private TemplateRow mapTemplateRow(ResultSet rs, int rowNum) throws SQLException {
        return new TemplateRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private AdminSpecTemplateValueResponse mapValue(ResultSet rs, int rowNum) throws SQLException {
        return new AdminSpecTemplateValueResponse(
                rs.getLong("id"),
                rs.getString("value_key"),
                rs.getString("value_name"),
                rs.getInt("sort_order")
        );
    }

    private record TemplateRow(Long id, String name, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    private record GroupRow(Long id, String groupKey, String name, boolean imageEnabled, Integer sortOrder) {
    }
}
