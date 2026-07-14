package org.muybaby.shopserver.coupon.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.CouponScopeType;
import org.muybaby.shopserver.coupon.CouponTemplateStatus;
import org.muybaby.shopserver.coupon.CouponType;
import org.muybaby.shopserver.coupon.DiscountType;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminCouponService {

    private static final String DEFAULT_STRATEGY_KEY = "coupon.amount-off.v1";

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public AdminCouponService(JdbcClient jdbcClient, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Transactional
    public Long create(AdminCouponTemplateRequest request) {
        ValidatedTemplate validated = validateRequest(request, null);
        requireScope(validated, CouponScopeType.ALL);
        return insert(validated);
    }

    @Transactional
    public Long createProductScoped(AdminCouponTemplateRequest request) {
        ValidatedTemplate validated = validateRequest(request, null);
        requireScope(validated, CouponScopeType.PRODUCT);
        return insert(validated);
    }

    private Long insert(ValidatedTemplate validated) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into coupon_template (
                            name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                            scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                            valid_start_at, valid_end_at, status, sort_order
                        )
                        values (
                            :name, :description, :couponType, :discountType, :thresholdCent, :discountCent,
                            :scopeType, :scopeValue, :strategyKey, :totalStock, 0, :perUserLimit,
                            :validStartAt, :validEndAt, :status, :sortOrder
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("name", validated.name())
                        .addValue("description", validated.description())
                        .addValue("couponType", validated.couponType().name())
                        .addValue("discountType", validated.discountType().name())
                        .addValue("thresholdCent", validated.thresholdCent())
                        .addValue("discountCent", validated.discountCent())
                        .addValue("scopeType", validated.scopeType().name())
                        .addValue("scopeValue", validated.scopeValue())
                        .addValue("strategyKey", validated.strategyKey())
                        .addValue("totalStock", validated.totalStock())
                        .addValue("perUserLimit", validated.perUserLimit())
                        .addValue("validStartAt", validated.validStartAt())
                        .addValue("validEndAt", validated.validEndAt())
                        .addValue("status", validated.status().name())
                        .addValue("sortOrder", validated.sortOrder()),
                keyHolder,
                new String[]{"id"});
        Long templateId = requireGeneratedId(keyHolder);
        syncProductBinding(templateId, validated);
        return templateId;
    }

    @Transactional
    public void update(Long templateId, AdminCouponTemplateRequest request) {
        TemplateState existing = findTemplateState(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_UNAVAILABLE));
        ValidatedTemplate validated = validateRequest(request, existing.claimedCount());
        if (existing.scopeType() != CouponScopeType.ALL) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        requireScope(validated, CouponScopeType.ALL);
        int updatedRows = jdbcClient.sql("""
                        update coupon_template
                        set name = :name,
                            description = :description,
                            coupon_type = :couponType,
                            discount_type = :discountType,
                            threshold_cent = :thresholdCent,
                            discount_cent = :discountCent,
                            scope_type = :scopeType,
                            scope_value = :scopeValue,
                            strategy_key = :strategyKey,
                            total_stock = :totalStock,
                            per_user_limit = :perUserLimit,
                            valid_start_at = :validStartAt,
                            valid_end_at = :validEndAt,
                            status = :status,
                            sort_order = :sortOrder,
                            updated_at = :updatedAt
                        where id = :templateId
                        """)
                .param("name", validated.name())
                .param("description", validated.description())
                .param("couponType", validated.couponType().name())
                .param("discountType", validated.discountType().name())
                .param("thresholdCent", validated.thresholdCent())
                .param("discountCent", validated.discountCent())
                .param("scopeType", validated.scopeType().name())
                .param("scopeValue", validated.scopeValue())
                .param("strategyKey", validated.strategyKey())
                .param("totalStock", validated.totalStock())
                .param("perUserLimit", validated.perUserLimit())
                .param("validStartAt", validated.validStartAt())
                .param("validEndAt", validated.validEndAt())
                .param("status", validated.status().name())
                .param("sortOrder", validated.sortOrder())
                .param("updatedAt", LocalDateTime.now())
                .param("templateId", templateId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
        syncProductBinding(templateId, validated);
    }

    @Transactional
    public void enable(Long templateId) {
        updateStatus(templateId, CouponTemplateStatus.ENABLED);
    }

    @Transactional
    public void disable(Long templateId) {
        updateStatus(templateId, CouponTemplateStatus.DISABLED);
    }

    private void updateStatus(Long templateId, CouponTemplateStatus status) {
        int updatedRows = jdbcClient.sql("""
                        update coupon_template
                        set status = :status,
                            updated_at = :updatedAt
                        where id = :templateId
                        """)
                .param("status", status.name())
                .param("updatedAt", LocalDateTime.now())
                .param("templateId", templateId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.COUPON_UNAVAILABLE);
        }
    }

    private Optional<TemplateState> findTemplateState(Long templateId) {
        return jdbcClient.sql("""
                        select claimed_count, scope_type
                        from coupon_template
                        where id = :templateId
                        """)
                .param("templateId", templateId)
                .query((rs, rowNum) -> new TemplateState(
                        rs.getInt("claimed_count"),
                        parseEnum(rs.getString("scope_type"), CouponScopeType.class)
                ))
                .optional();
    }

    private void requireScope(ValidatedTemplate template, CouponScopeType requiredScope) {
        if (template.scopeType() != requiredScope) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private ValidatedTemplate validateRequest(AdminCouponTemplateRequest request, Integer claimedCount) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String normalizedName = request.name() == null ? null : request.name().trim();
        if (!StringUtils.hasText(normalizedName) || normalizedName.length() > 80) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        CouponType couponType = parseEnum(request.couponType(), CouponType.class);
        DiscountType discountType = parseEnum(request.discountType(), DiscountType.class);
        CouponScopeType scopeType = parseEnum(request.scopeType(), CouponScopeType.class);
        CouponTemplateStatus status = parseEnum(request.status(), CouponTemplateStatus.class);

        if (discountType != DiscountType.AMOUNT_OFF) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String scopeValue = validateScope(scopeType, request.scopeValue());

        Long thresholdCent = request.thresholdCent();
        if (thresholdCent == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (couponType == CouponType.NO_THRESHOLD && thresholdCent != 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (couponType == CouponType.MIN_SPEND && thresholdCent <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Long discountCent = request.discountCent();
        if (discountCent == null || discountCent <= 0L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (couponType == CouponType.MIN_SPEND && discountCent >= thresholdCent) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Integer totalStock = request.totalStock();
        if (totalStock == null || totalStock <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (claimedCount != null && totalStock < claimedCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Integer perUserLimit = request.perUserLimit();
        if (perUserLimit == null || perUserLimit <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        LocalDateTime validStartAt = request.validStartAt();
        LocalDateTime validEndAt = request.validEndAt();
        if (validStartAt == null || validEndAt == null || !validStartAt.isBefore(validEndAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        return new ValidatedTemplate(
                normalizedName,
                defaultString(request.description()),
                couponType,
                discountType,
                thresholdCent,
                discountCent,
                scopeType,
                scopeValue,
                defaultStrategyKey(request.strategyKey()),
                totalStock,
                perUserLimit,
                validStartAt,
                validEndAt,
                status,
                request.sortOrder() == null ? 0 : request.sortOrder()
        );
    }

    private String validateScope(CouponScopeType scopeType, String requestedScopeValue) {
        if (scopeType == CouponScopeType.ALL) {
            if (StringUtils.hasText(requestedScopeValue)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return "";
        }
        if (scopeType != CouponScopeType.PRODUCT || !StringUtils.hasText(requestedScopeValue)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        long spuId;
        try {
            spuId = Long.parseLong(requestedScopeValue.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (spuId <= 0L || !productExists(spuId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return Long.toString(spuId);
    }

    private boolean productExists(long spuId) {
        return jdbcClient.sql("""
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
                .isPresent();
    }

    private void syncProductBinding(Long templateId, ValidatedTemplate template) {
        if (template.scopeType() != CouponScopeType.PRODUCT) {
            return;
        }
        long spuId = Long.parseLong(template.scopeValue());
        jdbcClient.sql("""
                        delete from product_spu_coupon
                        where coupon_template_id = :templateId
                        """)
                .param("templateId", templateId)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_coupon (spu_id, coupon_template_id)
                        values (:spuId, :templateId)
                        """)
                .param("spuId", spuId)
                .param("templateId", templateId)
                .update();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String defaultStrategyKey(String strategyKey) {
        return StringUtils.hasText(strategyKey) ? strategyKey.trim() : DEFAULT_STRATEGY_KEY;
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Failed to retrieve generated key");
    }

    private record ValidatedTemplate(
            String name,
            String description,
            CouponType couponType,
            DiscountType discountType,
            Long thresholdCent,
            Long discountCent,
            CouponScopeType scopeType,
            String scopeValue,
            String strategyKey,
            Integer totalStock,
            Integer perUserLimit,
            LocalDateTime validStartAt,
            LocalDateTime validEndAt,
            CouponTemplateStatus status,
            Integer sortOrder
    ) {
    }

    private record TemplateState(Integer claimedCount, CouponScopeType scopeType) {
    }
}
