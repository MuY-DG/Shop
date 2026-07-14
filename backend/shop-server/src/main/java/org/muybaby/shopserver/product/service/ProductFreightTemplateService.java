package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.FreightChargeMode;
import org.muybaby.shopserver.product.FreightTemplateStatus;
import org.muybaby.shopserver.product.ProductStatus;
import org.muybaby.shopserver.product.dto.AdminFreightTemplateRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
public class ProductFreightTemplateService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public ProductFreightTemplateService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Transactional
    public Long create(AdminFreightTemplateRequest request) {
        ValidatedFreightTemplate validated = validate(request);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into freight_template
                            (name, charge_mode, fixed_amount_cent, status, sort_order)
                        values
                            (:name, :chargeMode, :fixedAmountCent, :status, :sortOrder)
                        """,
                parameters(validated),
                keyHolder,
                new String[]{"id"});
        return Optional.ofNullable(keyHolder.getKey())
                .map(Number::longValue)
                .orElseThrow(this::validationException);
    }

    @Transactional
    public void update(Long templateId, AdminFreightTemplateRequest request) {
        ValidatedFreightTemplate validated = validate(request);
        lockActiveTemplate(templateId);
        if (FreightTemplateStatus.DISABLED.name().equals(validated.status())
                && hasActiveOnSaleProduct(templateId)) {
            throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        }
        int updatedRows = jdbcClient.sql("""
                        update freight_template
                        set name = :name,
                            charge_mode = :chargeMode,
                            fixed_amount_cent = :fixedAmountCent,
                            status = :status,
                            sort_order = :sortOrder,
                            updated_at = current_timestamp
                        where id = :templateId
                          and deleted_at is null
                        """)
                .param("name", validated.name())
                .param("chargeMode", validated.chargeMode().name())
                .param("fixedAmountCent", validated.fixedAmountCent())
                .param("status", validated.status())
                .param("sortOrder", validated.sortOrder())
                .param("templateId", templateId)
                .update();
        if (updatedRows != 1) {
            throw validationException();
        }
    }

    private void lockActiveTemplate(Long templateId) {
        if (templateId == null || templateId <= 0L) {
            throw validationException();
        }
        jdbcClient.sql("""
                        select id
                        from freight_template
                        where id = :templateId
                          and deleted_at is null
                        for update
                        """)
                .param("templateId", templateId)
                .query(Long.class)
                .optional()
                .orElseThrow(this::validationException);
    }

    private boolean hasActiveOnSaleProduct(Long templateId) {
        Long count = jdbcClient.sql("""
                        select count(*)
                        from product_spu
                        where freight_template_id = :templateId
                          and deleted_at is null
                          and status = :status
                        """)
                .param("templateId", templateId)
                .param("status", ProductStatus.ON_SALE.name())
                .query(Long.class)
                .single();
        return count != null && count > 0L;
    }

    private MapSqlParameterSource parameters(ValidatedFreightTemplate template) {
        return new MapSqlParameterSource()
                .addValue("name", template.name())
                .addValue("chargeMode", template.chargeMode().name())
                .addValue("fixedAmountCent", template.fixedAmountCent())
                .addValue("status", template.status())
                .addValue("sortOrder", template.sortOrder());
    }

    private ValidatedFreightTemplate validate(AdminFreightTemplateRequest request) {
        if (request == null
                || !StringUtils.hasText(request.name())
                || request.name().trim().length() > 64
                || request.chargeMode() == null
                || request.status() == null) {
            throw validationException();
        }
        long fixedAmountCent = request.fixedAmountCent() == null ? 0L : request.fixedAmountCent();
        if (request.chargeMode() == FreightChargeMode.FREE && fixedAmountCent != 0L) {
            throw validationException();
        }
        if (request.chargeMode() == FreightChargeMode.FIXED && fixedAmountCent <= 0L) {
            throw validationException();
        }
        return new ValidatedFreightTemplate(
                request.name().trim(),
                request.chargeMode(),
                fixedAmountCent,
                request.status().name(),
                request.sortOrder() == null ? 0 : request.sortOrder()
        );
    }

    private BusinessException validationException() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record ValidatedFreightTemplate(
            String name,
            FreightChargeMode chargeMode,
            long fixedAmountCent,
            String status,
            int sortOrder
    ) {
    }
}
