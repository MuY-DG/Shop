package org.muybaby.shopserver.compliance.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.compliance.PublicationStatus;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationDraftRequest;
import org.muybaby.shopserver.compliance.dto.MerchantPublicationResponse;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class MerchantComplianceService {

    private static final Pattern CREDIT_CODE = Pattern.compile("[0-9A-Z]{18}");
    private static final Pattern PHONE = Pattern.compile("[0-9+()\\-\\s]{5,32}");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;

    public MerchantComplianceService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
    }

    @Transactional(readOnly = true)
    public MerchantPublicationResponse currentPublished() {
        return findByPredicate("publication.current_publication_key = 1", Map.of()).stream()
                .findFirst()
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<MerchantPublicationResponse> history() {
        return findByPredicate("1 = 1", Map.of());
    }

    @Transactional
    public MerchantPublicationResponse createDraft(MerchantPublicationDraftRequest request, long adminUserId) {
        MerchantPublicationDraftRequest normalized = request == null
                ? new MerchantPublicationDraftRequest(null, null, null, null, null, null,
                null, null, null, null, null, null)
                : request;
        validateOptionalAssets(normalized);
        long revisionNo = nextRevisionNo();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into merchant_publication_revision (
                                revision_no, status, legal_name, entity_type,
                                unified_social_credit_code, business_address,
                                customer_service_phone, complaint_phone,
                                business_license_asset_id, food_qualification_type,
                                food_qualification_number, food_qualification_asset_id,
                                food_qualification_valid_from, food_qualification_valid_until,
                                created_by, created_at, updated_at
                            ) values (
                                :revisionNo, 'DRAFT', :legalName, :entityType,
                                :creditCode, :businessAddress, :customerServicePhone, :complaintPhone,
                                :businessLicenseAssetId, :foodQualificationType,
                                :foodQualificationNumber, :foodQualificationAssetId,
                                :validFrom, :validUntil, :createdBy, :now, :now
                            )
                            """,
                    new MapSqlParameterSource()
                            .addValue("revisionNo", revisionNo)
                            .addValue("legalName", normalize(normalized.legalName()))
                            .addValue("entityType", normalize(normalized.entityType()))
                            .addValue("creditCode", normalize(normalized.unifiedSocialCreditCode()).toUpperCase())
                            .addValue("businessAddress", normalize(normalized.businessAddress()))
                            .addValue("customerServicePhone", normalize(normalized.customerServicePhone()))
                            .addValue("complaintPhone", normalize(normalized.complaintPhone()))
                            .addValue("businessLicenseAssetId", normalized.businessLicenseAssetId())
                            .addValue("foodQualificationType", normalize(normalized.foodQualificationType()))
                            .addValue("foodQualificationNumber", normalize(normalized.foodQualificationNumber()))
                            .addValue("foodQualificationAssetId", normalized.foodQualificationAssetId())
                            .addValue("validFrom", normalized.foodQualificationValidFrom())
                            .addValue("validUntil", normalized.foodQualificationValidUntil())
                            .addValue("createdBy", adminUserId)
                            .addValue("now", LocalDateTime.now(ZoneOffset.UTC)),
                    keyHolder,
                    new String[]{"id"});
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        long id = generatedId(keyHolder);
        syncAssetUsages(id, normalized);
        return require(id);
    }

    @Transactional
    public MerchantPublicationResponse publish(long id, long adminUserId) {
        MerchantPublicationResponse candidate = requireForUpdate(id);
        if (!PublicationStatus.DRAFT.name().equals(candidate.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        validatePublishable(candidate);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                        update merchant_publication_revision
                        set status = 'SUPERSEDED', current_publication_key = null, updated_at = :now
                        where current_publication_key = 1 and id <> :id
                        """)
                .param("now", now)
                .param("id", id)
                .update();
        int updated;
        try {
            updated = jdbcClient.sql("""
                            update merchant_publication_revision
                            set status = 'PUBLISHED', current_publication_key = 1,
                                published_by = :publishedBy, published_at = :publishedAt,
                                updated_at = :publishedAt
                            where id = :id and status = 'DRAFT' and current_publication_key is null
                            """)
                    .param("publishedBy", adminUserId)
                    .param("publishedAt", now)
                    .param("id", id)
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return require(id);
    }

    private void validateOptionalAssets(MerchantPublicationDraftRequest request) {
        if (request.businessLicenseAssetId() != null) {
            storageUsageService.requireActivePublicMedia(
                    request.businessLicenseAssetId(), StorageMediaKind.IMAGE);
        }
        if (request.foodQualificationAssetId() != null) {
            storageUsageService.requireActivePublicMedia(
                    request.foodQualificationAssetId(), StorageMediaKind.IMAGE);
        }
    }

    private void syncAssetUsages(long id, MerchantPublicationDraftRequest request) {
        List<StorageUsageService.UsageAssignment> assignments = new ArrayList<>();
        if (request.businessLicenseAssetId() != null) {
            assignments.add(new StorageUsageService.UsageAssignment(
                    request.businessLicenseAssetId(),
                    StorageFileUsageType.MERCHANT_BUSINESS_LICENSE,
                    assetUrl(request.businessLicenseAssetId()), 0, true));
        }
        if (request.foodQualificationAssetId() != null) {
            assignments.add(new StorageUsageService.UsageAssignment(
                    request.foodQualificationAssetId(),
                    StorageFileUsageType.MERCHANT_FOOD_QUALIFICATION,
                    assetUrl(request.foodQualificationAssetId()), 1, true));
        }
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.MERCHANT_PUBLICATION,
                id,
                "商家经营资质修订 " + id,
                assignments);
    }

    /**
     * 发布不再要求字段非空：允许保存并发布留空的资质信息。
     * 仅在字段有值时做基础格式校验，保证已填写内容本身合法。
     */
    private void validatePublishable(MerchantPublicationResponse candidate) {
        if (StringUtils.hasText(candidate.unifiedSocialCreditCode())
                && !CREDIT_CODE.matcher(candidate.unifiedSocialCreditCode()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (StringUtils.hasText(candidate.customerServicePhone())
                && !PHONE.matcher(candidate.customerServicePhone()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (StringUtils.hasText(candidate.complaintPhone())
                && !PHONE.matcher(candidate.complaintPhone()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (candidate.foodQualificationValidFrom() != null
                && candidate.foodQualificationValidUntil() != null
                && candidate.foodQualificationValidUntil().isBefore(candidate.foodQualificationValidFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (candidate.businessLicenseAssetId() != null) {
            storageUsageService.requireActivePublicMedia(
                    candidate.businessLicenseAssetId(), StorageMediaKind.IMAGE);
        }
        if (candidate.foodQualificationAssetId() != null) {
            storageUsageService.requireActivePublicMedia(
                    candidate.foodQualificationAssetId(), StorageMediaKind.IMAGE);
        }
    }

    private long nextRevisionNo() {
        Long current = jdbcClient.sql("select coalesce(max(revision_no), 0) from merchant_publication_revision")
                .query(Long.class)
                .single();
        return Math.addExact(current, 1L);
    }

    private MerchantPublicationResponse require(long id) {
        return findByPredicate("publication.id = :id", Map.of("id", id)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private MerchantPublicationResponse requireForUpdate(long id) {
        MerchantPublicationResponse row = jdbcClient.sql("""
                        select publication.id, publication.revision_no, publication.status,
                               publication.legal_name, publication.entity_type,
                               publication.unified_social_credit_code, publication.business_address,
                               publication.customer_service_phone, publication.complaint_phone,
                               publication.business_license_asset_id,
                               coalesce(business_license.public_url, '') as business_license_url,
                               publication.food_qualification_type,
                               publication.food_qualification_number,
                               publication.food_qualification_asset_id,
                               coalesce(food_qualification.public_url, '') as food_qualification_url,
                               publication.food_qualification_valid_from,
                               publication.food_qualification_valid_until,
                               publication.created_by, publication.published_by,
                               publication.published_at, publication.created_at, publication.updated_at
                        from merchant_publication_revision publication
                        left join storage_asset business_license
                          on business_license.id = publication.business_license_asset_id
                        left join storage_asset food_qualification
                          on food_qualification.id = publication.food_qualification_asset_id
                        where publication.id = :id
                        for update
                        """)
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return row;
    }

    private List<MerchantPublicationResponse> findByPredicate(String predicate, Map<String, ?> parameters) {
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        select publication.id, publication.revision_no, publication.status,
                               publication.legal_name, publication.entity_type,
                               publication.unified_social_credit_code, publication.business_address,
                               publication.customer_service_phone, publication.complaint_phone,
                               publication.business_license_asset_id,
                               coalesce(business_license.public_url, '') as business_license_url,
                               publication.food_qualification_type,
                               publication.food_qualification_number,
                               publication.food_qualification_asset_id,
                               coalesce(food_qualification.public_url, '') as food_qualification_url,
                               publication.food_qualification_valid_from,
                               publication.food_qualification_valid_until,
                               publication.created_by, publication.published_by,
                               publication.published_at, publication.created_at, publication.updated_at
                        from merchant_publication_revision publication
                        left join storage_asset business_license
                          on business_license.id = publication.business_license_asset_id
                        left join storage_asset food_qualification
                          on food_qualification.id = publication.food_qualification_asset_id
                        where %s
                        order by publication.revision_no desc, publication.id desc
                        """.formatted(predicate));
        if (!parameters.isEmpty()) {
            statement = statement.params(parameters);
        }
        return statement.query(this::map).list();
    }

    private MerchantPublicationResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new MerchantPublicationResponse(
                rs.getLong("id"),
                rs.getLong("revision_no"),
                rs.getString("status"),
                rs.getString("legal_name"),
                rs.getString("entity_type"),
                rs.getString("unified_social_credit_code"),
                rs.getString("business_address"),
                rs.getString("customer_service_phone"),
                rs.getString("complaint_phone"),
                nullableLong(rs, "business_license_asset_id"),
                rs.getString("business_license_url"),
                rs.getString("food_qualification_type"),
                rs.getString("food_qualification_number"),
                nullableLong(rs, "food_qualification_asset_id"),
                rs.getString("food_qualification_url"),
                rs.getObject("food_qualification_valid_from", LocalDate.class),
                rs.getObject("food_qualification_valid_until", LocalDate.class),
                rs.getLong("created_by"),
                nullableLong(rs, "published_by"),
                rs.getObject("published_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private String assetUrl(long assetId) {
        return jdbcClient.sql("""
                        select public_url from storage_asset
                        where id = :assetId and scope = 'LIBRARY' and media_kind = 'IMAGE'
                          and visibility = 'PUBLIC' and status = 'ACTIVE'
                        """)
                .param("assetId", assetId)
                .query(String.class)
                .optional()
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private long generatedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Merchant publication id was not generated");
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
