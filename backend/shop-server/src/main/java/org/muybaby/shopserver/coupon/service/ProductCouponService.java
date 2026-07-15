package org.muybaby.shopserver.coupon.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.CouponScopeType;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateResponse;
import org.muybaby.shopserver.coupon.dto.ProductCouponBindingRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProductCouponService {

    private static final String PUBLIC_DISTRIBUTION_MODE = "PUBLIC";

    private final JdbcClient jdbcClient;
    private final AdminCouponService adminCouponService;

    public ProductCouponService(JdbcClient jdbcClient, AdminCouponService adminCouponService) {
        this.jdbcClient = jdbcClient;
        this.adminCouponService = adminCouponService;
    }

    public List<AdminCouponTemplateResponse> adminCoupons(Long spuId) {
        requireProduct(spuId);
        return jdbcClient.sql("""
                        select t.id, t.name, t.description, t.coupon_type, t.discount_type,
                               t.threshold_cent, t.discount_cent, t.scope_type, t.scope_value,
                               t.strategy_key, t.total_stock, t.claimed_count,
                               greatest(t.total_stock - t.claimed_count, 0) as stock_remaining,
                               t.per_user_limit, t.valid_start_at, t.valid_end_at, t.status,
                               t.sort_order, t.created_at, t.updated_at
                        from product_spu_coupon pc
                        join coupon_template t on t.id = pc.coupon_template_id
                        where pc.spu_id = :spuId
                          and t.distribution_mode = :distributionMode
                        order by t.sort_order asc, t.id desc
                        """)
                .param("spuId", spuId)
                .param("distributionMode", PUBLIC_DISTRIBUTION_MODE)
                .query(this::mapAdminCouponTemplate)
                .list();
    }

    @Transactional
    public void replaceBindings(Long spuId, ProductCouponBindingRequest request) {
        lockActiveProduct(spuId);
        if (request == null || request.couponTemplateIds() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Set<Long> templateIds = new LinkedHashSet<>();
        for (Long templateId : request.couponTemplateIds()) {
            if (templateId == null || templateId <= 0L || !templateIds.add(templateId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            validateBinding(spuId, templateId);
        }
        templateIds.addAll(requiredProductCouponIds(spuId));

        jdbcClient.sql("""
                        delete from product_spu_coupon
                        where spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .update();
        for (Long templateId : templateIds) {
            jdbcClient.sql("""
                            insert into product_spu_coupon (spu_id, coupon_template_id)
                            values (:spuId, :templateId)
                            """)
                    .param("spuId", spuId)
                    .param("templateId", templateId)
                    .update();
        }
    }

    @Transactional
    public Long createProductCoupon(Long spuId, AdminCouponTemplateRequest request) {
        lockActiveProduct(spuId);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        AdminCouponTemplateRequest scopedRequest = new AdminCouponTemplateRequest(
                request.name(),
                request.description(),
                request.couponType(),
                request.discountType(),
                request.thresholdCent(),
                request.discountCent(),
                CouponScopeType.PRODUCT.name(),
                Long.toString(spuId),
                request.strategyKey(),
                request.totalStock(),
                request.perUserLimit(),
                request.validStartAt(),
                request.validEndAt(),
                request.status(),
                request.sortOrder()
        );
        return adminCouponService.createProductScoped(scopedRequest);
    }

    private void validateBinding(Long spuId, Long templateId) {
        CouponScopeRow scope = jdbcClient.sql("""
                        select scope_type, scope_value
                        from coupon_template
                        where id = :templateId
                          and distribution_mode = :distributionMode
                        """)
                .param("templateId", templateId)
                .param("distributionMode", PUBLIC_DISTRIBUTION_MODE)
                .query((rs, rowNum) -> new CouponScopeRow(
                        rs.getString("scope_type"),
                        rs.getString("scope_value")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));

        if (CouponScopeType.ALL.name().equals(scope.scopeType())) {
            if (!StringUtils.hasText(scope.scopeValue())) {
                return;
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (CouponScopeType.PRODUCT.name().equals(scope.scopeType())
                && Long.toString(spuId).equals(scope.scopeValue())) {
            return;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private List<Long> requiredProductCouponIds(Long spuId) {
        return jdbcClient.sql("""
                        select id
                        from coupon_template
                        where scope_type = :scopeType
                          and distribution_mode = :distributionMode
                          and scope_value = :scopeValue
                        order by id
                        """)
                .param("scopeType", CouponScopeType.PRODUCT.name())
                .param("distributionMode", PUBLIC_DISTRIBUTION_MODE)
                .param("scopeValue", Long.toString(spuId))
                .query(Long.class)
                .list();
    }

    private void requireProduct(Long spuId) {
        if (spuId == null || spuId <= 0L) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from product_spu
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        if (count == null || count != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
    }

    private void lockActiveProduct(Long spuId) {
        if (spuId == null || spuId <= 0L) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        jdbcClient.sql("""
                        select id
                        from product_spu
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        for update
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE));
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
                rs.getObject("updated_at", LocalDateTime.class),
                "PUBLIC",
                null,
                null,
                null
        );
    }

    private record CouponScopeRow(String scopeType, String scopeValue) {
    }
}
