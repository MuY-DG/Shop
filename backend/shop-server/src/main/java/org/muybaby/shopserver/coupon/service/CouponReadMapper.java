package org.muybaby.shopserver.coupon.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateQueryRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CouponReadMapper {

    private final JdbcClient jdbcClient;

    public CouponReadMapper(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<AdminCouponTemplateResponse> adminTemplatePage(AdminCouponTemplateQueryRequest query) {
        AdminCouponTemplateQueryRequest normalizedQuery = query == null
                ? new AdminCouponTemplateQueryRequest(null, null, null, null)
                : query;
        long current = normalizedQuery.pageCurrent();
        long size = normalizedQuery.pageSize();
        long offset = (current - 1) * size;

        String nameLike = StringUtils.hasText(normalizedQuery.name()) ? "%" + normalizedQuery.name().trim() + "%" : null;
        String status = StringUtils.hasText(normalizedQuery.status()) ? normalizedQuery.status().trim() : null;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from coupon_template
                        where (:nameLike is null or name like :nameLike)
                          and (:status is null or status = :status)
                        """)
                .param("nameLike", nameLike)
                .param("status", status)
                .query(Long.class)
                .single();

        List<AdminCouponTemplateResponse> records = jdbcClient.sql("""
                        select id, name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                               scope_type, scope_value, strategy_key, total_stock, claimed_count,
                               greatest(total_stock - claimed_count, 0) as stock_remaining,
                               per_user_limit, valid_start_at, valid_end_at, status, sort_order, created_at, updated_at
                        from coupon_template
                        where (:nameLike is null or name like :nameLike)
                          and (:status is null or status = :status)
                        order by sort_order asc, id desc
                        limit :limit offset :offset
                        """)
                .param("nameLike", nameLike)
                .param("status", status)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAdminCouponTemplate)
                .list();

        return PageResult.of(records, total == null ? 0 : total, current, size);
    }

    private AdminCouponTemplateResponse mapAdminCouponTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new AdminCouponTemplateResponse(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("coupon_type"),
                rs.getString("discount_type"),
                rs.getLong("threshold_cent"),
                rs.getLong("discount_cent"),
                rs.getString("scope_type"),
                rs.getString("scope_value"),
                rs.getString("strategy_key"),
                rs.getInt("total_stock"),
                rs.getInt("claimed_count"),
                rs.getInt("stock_remaining"),
                rs.getInt("per_user_limit"),
                rs.getObject("valid_start_at", LocalDateTime.class),
                rs.getObject("valid_end_at", LocalDateTime.class),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }
}
