package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.product.FreightChargeMode;
import org.muybaby.shopserver.product.FreightTemplateStatus;
import org.muybaby.shopserver.product.dto.AdminFreightTemplateResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Service
public class ProductFreightTemplateReadMapper {

    private final JdbcClient jdbcClient;

    public ProductFreightTemplateReadMapper(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AdminFreightTemplateResponse> list() {
        return jdbcClient.sql("""
                        select id, name, charge_mode, fixed_amount_cent, status,
                               sort_order, created_at, updated_at
                        from freight_template
                        where deleted_at is null
                        order by sort_order asc, id desc
                        """)
                .query(this::mapResponse)
                .list();
    }

    public Optional<AdminFreightTemplateResponse> findById(Long templateId) {
        return jdbcClient.sql("""
                        select id, name, charge_mode, fixed_amount_cent, status,
                               sort_order, created_at, updated_at
                        from freight_template
                        where id = :templateId
                          and deleted_at is null
                        """)
                .param("templateId", templateId)
                .query(this::mapResponse)
                .optional();
    }

    private AdminFreightTemplateResponse mapResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminFreightTemplateResponse(
                rs.getLong("id"),
                rs.getString("name"),
                FreightChargeMode.valueOf(rs.getString("charge_mode")),
                rs.getLong("fixed_amount_cent"),
                FreightTemplateStatus.valueOf(rs.getString("status")),
                rs.getInt("sort_order"),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("updated_at", java.time.LocalDateTime.class)
        );
    }
}
