package org.muybaby.shopserver.product.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceRequest;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceResponse;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class ProductGuaranteeServiceService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ProductGuaranteeServiceReadMapper readMapper;
    private final StorageUsageService storageUsageService;

    public ProductGuaranteeServiceService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            ProductGuaranteeServiceReadMapper readMapper,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.readMapper = readMapper;
        this.storageUsageService = storageUsageService;
    }

    @Transactional
    public Long create(AdminGuaranteeServiceRequest request) {
        ValidatedService validated = validate(request, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into product_guarantee_service
                            (terms_name, content_description, icon, icon_file_id, sort_order, visible)
                        values
                            (:termsName, :contentDescription, :icon, :iconFileId, :sortOrder, :visible)
                        """,
                new MapSqlParameterSource()
                        .addValue("termsName", validated.termsName())
                        .addValue("contentDescription", validated.contentDescription())
                        .addValue("icon", validated.icon())
                        .addValue("iconFileId", validated.iconFileId())
                        .addValue("sortOrder", validated.sortOrder())
                        .addValue("visible", validated.visible()),
                keyHolder,
                new String[]{"id"});
        Long serviceId = requireGeneratedId(keyHolder);
        syncIconUsage(serviceId, validated);
        return serviceId;
    }

    @Transactional
    public void update(Long serviceId, AdminGuaranteeServiceRequest request) {
        AdminGuaranteeServiceResponse existing = requireService(serviceId);
        ValidatedService validated = validate(request, existing);
        int updatedRows = jdbcClient.sql("""
                        update product_guarantee_service
                        set terms_name = :termsName,
                            content_description = :contentDescription,
                            icon = :icon,
                            icon_file_id = :iconFileId,
                            sort_order = :sortOrder,
                            visible = :visible,
                            updated_at = current_timestamp
                        where id = :serviceId
                          and deleted_at is null
                        """)
                .param("termsName", validated.termsName())
                .param("contentDescription", validated.contentDescription())
                .param("icon", validated.icon())
                .param("iconFileId", validated.iconFileId())
                .param("sortOrder", validated.sortOrder())
                .param("visible", validated.visible())
                .param("serviceId", serviceId)
                .update();
        if (updatedRows != 1) {
            throw validationException();
        }
        syncIconUsage(serviceId, validated);
    }

    @Transactional
    public void updateVisibility(Long serviceId, boolean visible) {
        int updatedRows = jdbcClient.sql("""
                        update product_guarantee_service
                        set visible = :visible,
                            updated_at = current_timestamp
                        where id = :serviceId
                          and deleted_at is null
                        """)
                .param("visible", visible)
                .param("serviceId", serviceId)
                .update();
        if (updatedRows != 1) {
            throw validationException();
        }
    }

    @Transactional
    public void delete(Long serviceId) {
        lockActiveService(serviceId);
        jdbcClient.sql("""
                        delete from product_spu_guarantee_service
                        where service_id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .update();
        int updatedRows = jdbcClient.sql("""
                        update product_guarantee_service
                        set visible = false,
                            deleted_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :serviceId
                          and deleted_at is null
                        """)
                .param("serviceId", serviceId)
                .update();
        if (updatedRows != 1) {
            throw validationException();
        }
        storageUsageService.removeOwnerUsages(StorageUsageOwnerType.GUARANTEE_SERVICE, serviceId);
    }

    private void lockActiveService(Long serviceId) {
        if (serviceId == null || serviceId <= 0L) {
            throw validationException();
        }
        jdbcClient.sql("""
                        select id
                        from product_guarantee_service
                        where id = :serviceId
                          and deleted_at is null
                        for update
                        """)
                .param("serviceId", serviceId)
                .query(Long.class)
                .optional()
                .orElseThrow(this::validationException);
    }

    private AdminGuaranteeServiceResponse requireService(Long serviceId) {
        return readMapper.findById(serviceId).orElseThrow(this::validationException);
    }

    private ValidatedService validate(
            AdminGuaranteeServiceRequest request,
            AdminGuaranteeServiceResponse existing
    ) {
        if (request == null || request.visible() == null) {
            throw validationException();
        }
        String termsName = requireText(request.termsName(), 64);
        String contentDescription = requireText(request.contentDescription(), 500);
        String icon = requireText(request.icon(), 500);
        Long iconFileId = request.iconFileId();
        if (iconFileId == null && existing != null && icon.equals(existing.icon())) {
            iconFileId = existing.iconFileId();
        }
        return new ValidatedService(
                termsName,
                contentDescription,
                icon,
                iconFileId,
                request.sortOrder() == null ? 0 : request.sortOrder(),
                request.visible()
        );
    }

    private void syncIconUsage(Long serviceId, ValidatedService service) {
        if (service.iconFileId() != null) {
            storageUsageService.requireActivePublicMedia(service.iconFileId(), StorageMediaKind.IMAGE);
        }
        List<StorageUsageService.UsageAssignment> assignments = service.iconFileId() == null
                ? List.of()
                : List.of(new StorageUsageService.UsageAssignment(
                        service.iconFileId(),
                        StorageFileUsageType.GUARANTEE_SERVICE_ICON,
                        service.icon(),
                        service.sortOrder(),
                        false
                ));
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.GUARANTEE_SERVICE,
                serviceId,
                service.termsName(),
                assignments
        );
    }

    private String requireText(String value, int maxLength) {
        String normalized = StringUtils.hasText(value) ? value.trim() : null;
        if (normalized == null || normalized.length() > maxLength) {
            throw validationException();
        }
        return normalized;
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw validationException();
        }
        return key.longValue();
    }

    private BusinessException validationException() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record ValidatedService(
            String termsName,
            String contentDescription,
            String icon,
            Long iconFileId,
            int sortOrder,
            boolean visible
    ) {
    }
}
