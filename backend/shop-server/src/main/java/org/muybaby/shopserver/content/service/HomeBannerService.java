package org.muybaby.shopserver.content.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.content.HomeBannerJumpType;
import org.muybaby.shopserver.content.HomeBannerStatus;
import org.muybaby.shopserver.content.dto.AdminHomeBannerQueryRequest;
import org.muybaby.shopserver.content.dto.AdminHomeBannerRequest;
import org.muybaby.shopserver.content.dto.AdminHomeBannerResponse;
import org.muybaby.shopserver.content.dto.AppHomeBannerResponse;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class HomeBannerService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;

    public HomeBannerService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
    }

    public PageResult<AdminHomeBannerResponse> page(AdminHomeBannerQueryRequest query) {
        AdminHomeBannerQueryRequest normalized = query == null
                ? new AdminHomeBannerQueryRequest(null, null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from home_banner
                        where (:title is null or title like :titlePattern)
                          and (:status is null or status = :status)
                        """)
                .param("title", blankToNull(normalized.title()))
                .param("titlePattern", likePattern(normalized.title()))
                .param("status", blankToNull(normalized.status()))
                .query(Long.class)
                .single();

        List<AdminHomeBannerResponse> records = jdbcClient.sql("""
                        select id, title, subtitle, image_file_id, image_url, jump_type, jump_target_id, jump_path,
                               status, sort_order, start_at, end_at, created_at, updated_at
                        from home_banner
                        where (:title is null or title like :titlePattern)
                          and (:status is null or status = :status)
                        order by sort_order asc, id desc
                        limit :limit offset :offset
                        """)
                .param("title", blankToNull(normalized.title()))
                .param("titlePattern", likePattern(normalized.title()))
                .param("status", blankToNull(normalized.status()))
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAdminResponse)
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    @Transactional
    public Long create(AdminHomeBannerRequest request) {
        lockRequestedProductTarget(request);
        ValidatedBanner validated = validateRequest(request, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into home_banner
                            (title, subtitle, image_file_id, image_url, jump_type, jump_target_id, jump_path,
                             status, sort_order, start_at, end_at)
                        values
                            (:title, :subtitle, :imageFileId, :imageUrl, :jumpType, :jumpTargetId, :jumpPath,
                             :status, :sortOrder, :startAt, :endAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("title", validated.title())
                        .addValue("subtitle", validated.subtitle())
                        .addValue("imageFileId", validated.imageFileId())
                        .addValue("imageUrl", validated.imageUrl())
                        .addValue("jumpType", validated.jumpType().name())
                        .addValue("jumpTargetId", validated.jumpTargetId())
                        .addValue("jumpPath", validated.jumpPath())
                        .addValue("status", validated.status().name())
                        .addValue("sortOrder", validated.sortOrder())
                        .addValue("startAt", validated.startAt())
                        .addValue("endAt", validated.endAt()),
                keyHolder,
                new String[]{"id"});
        Long bannerId = requireGeneratedId(keyHolder);
        replaceUsage(bannerId, validated);
        return bannerId;
    }

    @Transactional
    public void update(Long bannerId, AdminHomeBannerRequest request) {
        lockRequestedProductTarget(request);
        BannerRow existing = requireBanner(bannerId);
        ValidatedBanner validated = validateRequest(request, existing);
        int updatedRows = jdbcClient.sql("""
                        update home_banner
                        set title = :title,
                            subtitle = :subtitle,
                            image_file_id = :imageFileId,
                            image_url = :imageUrl,
                            jump_type = :jumpType,
                            jump_target_id = :jumpTargetId,
                            jump_path = :jumpPath,
                            status = :status,
                            sort_order = :sortOrder,
                            start_at = :startAt,
                            end_at = :endAt,
                            updated_at = current_timestamp
                        where id = :bannerId
                        """)
                .param("title", validated.title())
                .param("subtitle", validated.subtitle())
                .param("imageFileId", validated.imageFileId())
                .param("imageUrl", validated.imageUrl())
                .param("jumpType", validated.jumpType().name())
                .param("jumpTargetId", validated.jumpTargetId())
                .param("jumpPath", validated.jumpPath())
                .param("status", validated.status().name())
                .param("sortOrder", validated.sortOrder())
                .param("startAt", validated.startAt())
                .param("endAt", validated.endAt())
                .param("bannerId", bannerId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        replaceUsage(bannerId, validated);
    }

    @Transactional
    public void enable(Long bannerId) {
        updateStatus(bannerId, HomeBannerStatus.ENABLED);
    }

    @Transactional
    public void disable(Long bannerId) {
        updateStatus(bannerId, HomeBannerStatus.DISABLED);
    }

    public List<AppHomeBannerResponse> appBanners() {
        LocalDateTime now = LocalDateTime.now();
        return jdbcClient.sql("""
                        select id, title, subtitle, image_url, jump_type, jump_target_id, jump_path
                        from home_banner
                        where status = 'ENABLED'
                          and (start_at is null or start_at <= :now)
                          and (end_at is null or end_at >= :now)
                          and (
                            jump_type <> 'PRODUCT'
                            or exists (
                                select 1
                                from product_spu s
                                where s.id = home_banner.jump_target_id
                                  and s.status = 'ON_SALE'
                                  and s.deleted_at is null
                                  and s.purged_at is null
                            )
                          )
                        order by sort_order asc, id desc
                        """)
                .param("now", now)
                .query((rs, rowNum) -> new AppHomeBannerResponse(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("subtitle"),
                        rs.getString("image_url"),
                        rs.getString("jump_type"),
                        rs.getObject("jump_target_id", Long.class),
                        rs.getString("jump_path")
                ))
                .list();
    }

    private void replaceUsage(Long bannerId, ValidatedBanner validated) {
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.HOME_BANNER,
                bannerId,
                validated.title(),
                List.of(new StorageUsageService.UsageAssignment(
                        validated.imageFileId(),
                        StorageFileUsageType.HOME_BANNER,
                        validated.imageUrl(),
                        validated.sortOrder(),
                        false
                ))
        );
    }

    private void updateStatus(Long bannerId, HomeBannerStatus status) {
        BannerTargetSnapshot target = findBannerTarget(bannerId);
        if (status == HomeBannerStatus.ENABLED && target.jumpType() == HomeBannerJumpType.PRODUCT) {
            lockActiveProductTarget(target.jumpTargetId());
        }
        int updatedRows = jdbcClient.sql("""
                        update home_banner
                        set status = :status,
                            updated_at = current_timestamp
                        where id = :bannerId
                          and jump_type = :jumpType
                          and ((jump_target_id is null and :jumpTargetId is null) or jump_target_id = :jumpTargetId)
                          and jump_path = :jumpPath
                        """)
                .param("status", status.name())
                .param("bannerId", bannerId)
                .param("jumpType", target.jumpType().name())
                .param("jumpTargetId", target.jumpTargetId())
                .param("jumpPath", target.jumpPath())
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void lockRequestedProductTarget(AdminHomeBannerRequest request) {
        if (request == null) {
            return;
        }
        HomeBannerJumpType jumpType = parseEnum(request.jumpType(), HomeBannerJumpType.class);
        if (jumpType != HomeBannerJumpType.PRODUCT) {
            return;
        }
        if (request.jumpTargetId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        lockActiveProductTarget(request.jumpTargetId());
    }

    private void lockActiveProductTarget(Long spuId) {
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

    private BannerTargetSnapshot findBannerTarget(Long bannerId) {
        return jdbcClient.sql("""
                        select jump_type, jump_target_id, jump_path
                        from home_banner
                        where id = :bannerId
                        """)
                .param("bannerId", bannerId)
                .query((rs, rowNum) -> new BannerTargetSnapshot(
                        parseEnum(rs.getString("jump_type"), HomeBannerJumpType.class),
                        rs.getObject("jump_target_id", Long.class),
                        rs.getString("jump_path")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private ValidatedBanner validateRequest(AdminHomeBannerRequest request, BannerRow existing) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String title = trimToNull(request.title());
        if (!StringUtils.hasText(title) || title.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String subtitle = trimToEmpty(request.subtitle());
        if (subtitle.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        if (request.imageFileId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        HomeBannerJumpType jumpType = parseEnum(request.jumpType(), HomeBannerJumpType.class);
        HomeBannerStatus status = parseEnum(request.status(), HomeBannerStatus.class);
        Integer sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        LocalDateTime startAt = request.startAt();
        LocalDateTime endAt = request.endAt();
        if (startAt != null && endAt != null && startAt.isAfter(endAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String imageUrl = resolveImageUrl(request.imageFileId(), existing);
        Long jumpTargetId = null;
        String jumpPath = "";
        switch (jumpType) {
            case NONE -> {
                jumpTargetId = null;
                jumpPath = "";
            }
            case PRODUCT, CATEGORY, COUPON -> {
                if (request.jumpTargetId() == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
                jumpTargetId = request.jumpTargetId();
                jumpPath = "";
            }
            case APP_PATH, URL -> {
                String normalizedPath = trimToNull(request.jumpPath());
                if (!StringUtils.hasText(normalizedPath)) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
                jumpTargetId = null;
                jumpPath = normalizedPath;
            }
        }

        return new ValidatedBanner(
                title,
                subtitle,
                request.imageFileId(),
                imageUrl,
                jumpType,
                jumpTargetId,
                jumpPath,
                status,
                sortOrder,
                startAt,
                endAt
        );
    }

    private String resolveImageUrl(Long imageFileId, BannerRow existing) {
        storageUsageService.requireActivePublicMedia(imageFileId, StorageMediaKind.IMAGE);
        if (existing != null
                && Objects.equals(existing.imageFileId(), imageFileId)
                && StringUtils.hasText(existing.imageUrl())) {
            return existing.imageUrl();
        }
        return jdbcClient.sql("""
                        select public_url
                        from storage_asset
                        where id = :fileId
                          and scope = 'LIBRARY'
                          and media_kind = 'IMAGE'
                          and status = 'ACTIVE'
                          and visibility = 'PUBLIC'
                        """)
                .param("fileId", imageFileId)
                .query(String.class)
                .optional()
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private BannerRow requireBanner(Long bannerId) {
        return jdbcClient.sql("""
                        select id, title, subtitle, image_file_id, image_url, jump_type, jump_target_id, jump_path,
                               status, sort_order, start_at, end_at, created_at, updated_at
                        from home_banner
                        where id = :bannerId
                        for update
                        """)
                .param("bannerId", bannerId)
                .query(this::mapBannerRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private AdminHomeBannerResponse mapAdminResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminHomeBannerResponse(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getObject("image_file_id", Long.class),
                rs.getString("image_url"),
                rs.getString("jump_type"),
                rs.getObject("jump_target_id", Long.class),
                rs.getString("jump_path"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("start_at", LocalDateTime.class),
                rs.getObject("end_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private BannerRow mapBannerRow(ResultSet rs, int rowNum) throws SQLException {
        return new BannerRow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getObject("image_file_id", Long.class),
                rs.getString("image_url"),
                rs.getString("jump_type"),
                rs.getObject("jump_target_id", Long.class),
                rs.getString("jump_path"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getObject("start_at", LocalDateTime.class),
                rs.getObject("end_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String likePattern(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : "%" + normalized + "%";
    }

    private String trimToNull(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType) {
        try {
            return Enum.valueOf(enumType, value.trim());
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

    private record ValidatedBanner(
            String title,
            String subtitle,
            Long imageFileId,
            String imageUrl,
            HomeBannerJumpType jumpType,
            Long jumpTargetId,
            String jumpPath,
            HomeBannerStatus status,
            Integer sortOrder,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
    }

    private record BannerRow(
            Long id,
            String title,
            String subtitle,
            Long imageFileId,
            String imageUrl,
            String jumpType,
            Long jumpTargetId,
            String jumpPath,
            String status,
            Integer sortOrder,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record BannerTargetSnapshot(
            HomeBannerJumpType jumpType,
            Long jumpTargetId,
            String jumpPath
    ) {
    }
}
