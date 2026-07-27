package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.dto.StorageAssetUsageQueryRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetUsageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StorageUsageService {

    private final JdbcClient jdbcClient;

    public StorageUsageService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<StorageAssetUsageResponse> usages(Long fileId) {
        return jdbcClient.sql("""
                        select id, asset_id, usage_type, owner_type, owner_id, owner_label, snapshot_url,
                               sort_order, protected, status, created_at, updated_at
                        from storage_asset_usage
                        where asset_id = :fileId
                        order by status asc, sort_order asc, id asc
                        """)
                .param("fileId", fileId)
                .query(this::mapUsage)
                .list();
    }

    public PageResult<StorageAssetUsageResponse> usagePage(
            Long fileId,
            StorageAssetUsageQueryRequest query
    ) {
        StorageAssetUsageQueryRequest normalized = query == null
                ? new StorageAssetUsageQueryRequest(null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        String status = normalized.pageStatus().name();

        Long total = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and status = :status
                        """)
                .param("fileId", fileId)
                .param("status", status)
                .query(Long.class)
                .single();

        List<StorageAssetUsageResponse> records = jdbcClient.sql("""
                        select id, asset_id, usage_type, owner_type, owner_id, owner_label, snapshot_url,
                               sort_order, protected, status, created_at, updated_at
                        from storage_asset_usage
                        where asset_id = :fileId
                          and status = :status
                        order by id desc
                        limit :size offset :offset
                        """)
                .param("fileId", fileId)
                .param("status", status)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapUsage)
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public boolean hasActiveUsages(Long fileId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and status = 'ACTIVE'
                        """)
                .param("fileId", fileId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    @Transactional
    public void requireActivePublicMedia(Long fileId, StorageMediaKind mediaKind) {
        ActiveAssetRow asset = jdbcClient.sql("""
                        select scope, media_kind, visibility, status
                        from storage_asset
                        where id = :fileId
                        for update
                        """)
                .param("fileId", fileId)
                .query((rs, rowNum) -> new ActiveAssetRow(
                        rs.getString("scope"),
                        rs.getString("media_kind"),
                        rs.getString("visibility"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        if (!"LIBRARY".equals(asset.scope())
                || !"ACTIVE".equals(asset.status())
                || !"PUBLIC".equals(asset.visibility())
                || mediaKind == null
                || !mediaKind.name().equals(asset.mediaKind())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    @Transactional
    public void replaceOwnerUsages(
            StorageUsageOwnerType ownerType,
            Long ownerId,
            String ownerLabel,
            List<UsageAssignment> usages
    ) {
        Map<UsageKey, UsageAssignment> desiredUsages = new LinkedHashMap<>();
        for (UsageAssignment usage : usages == null ? List.<UsageAssignment>of() : usages) {
            if (usage == null || usage.fileId() == null || usage.usageType() == null) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            desiredUsages.putIfAbsent(UsageKey.from(usage), usage);
        }

        desiredUsages.values().stream()
                .sorted(Comparator.comparing(UsageAssignment::fileId)
                        .thenComparing(usage -> usage.usageType().name()))
                .forEach(usage -> requireActiveAsset(usage.fileId()));

        List<ActiveUsageRow> activeUsages = jdbcClient.sql("""
                        select id, asset_id, usage_type
                        from storage_asset_usage
                        where owner_type = :ownerType
                          and owner_id = :ownerId
                          and status = 'ACTIVE'
                        order by id
                        for update
                        """)
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .query((rs, rowNum) -> new ActiveUsageRow(
                        rs.getLong("id"),
                        rs.getLong("asset_id"),
                        rs.getString("usage_type")
                ))
                .list();

        Set<UsageKey> retainedUsages = new HashSet<>();
        for (ActiveUsageRow activeUsage : activeUsages) {
            UsageKey key = activeUsage.key();
            UsageAssignment desiredUsage = desiredUsages.get(key);
            if (desiredUsage == null || !retainedUsages.add(key)) {
                removeUsage(activeUsage.id());
                continue;
            }
            updateUsage(activeUsage.id(), ownerLabel, desiredUsage);
        }

        for (Map.Entry<UsageKey, UsageAssignment> entry : desiredUsages.entrySet()) {
            if (!retainedUsages.contains(entry.getKey())) {
                UsageAssignment usage = entry.getValue();
                insertUsageRow(
                        usage.fileId(),
                        usage.usageType(),
                        ownerType,
                        ownerId,
                        ownerLabel,
                        usage.snapshotUrl(),
                        usage.sortOrder(),
                        usage.protectedUsage()
                );
            }
        }
    }

    @Transactional
    public void addProtectedUsage(
            Long fileId,
            StorageFileUsageType usageType,
            StorageUsageOwnerType ownerType,
            Long ownerId,
            String ownerLabel,
            String snapshotUrl,
            Integer sortOrder
    ) {
        insertUsage(fileId, usageType, ownerType, ownerId, ownerLabel, snapshotUrl, sortOrder, true);
    }

    @Transactional
    public void removeOwnerUsages(StorageUsageOwnerType ownerType, Long ownerId) {
        jdbcClient.sql("""
                        update storage_asset_usage
                        set status = 'REMOVED',
                            updated_at = current_timestamp
                        where owner_type = :ownerType
                          and owner_id = :ownerId
                          and status = 'ACTIVE'
                        """)
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .update();
    }

    @Transactional
    public boolean restoreOwnerUsageIfAvailable(
            StorageUsageOwnerType ownerType,
            Long ownerId,
            String ownerLabel,
            UsageAssignment usage
    ) {
        boolean activeAssetExists = jdbcClient.sql("""
                        select id
                        from storage_asset
                        where id = :fileId
                          and scope = 'LIBRARY'
                          and status = 'ACTIVE'
                          and visibility = 'PUBLIC'
                        for update
                        """)
                .param("fileId", usage.fileId())
                .query(Long.class)
                .optional()
                .isPresent();
        if (!activeAssetExists) {
            return false;
        }

        Integer activeUsageCount = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and usage_type = :usageType
                          and owner_type = :ownerType
                          and owner_id = :ownerId
                          and status = 'ACTIVE'
                        """)
                .param("fileId", usage.fileId())
                .param("usageType", usage.usageType().name())
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .query(Integer.class)
                .single();
        if (activeUsageCount != null && activeUsageCount > 0) {
            return true;
        }

        insertUsage(
                usage.fileId(),
                usage.usageType(),
                ownerType,
                ownerId,
                ownerLabel,
                usage.snapshotUrl(),
                usage.sortOrder(),
                usage.protectedUsage()
        );
        return true;
    }

    private void insertUsage(
            Long fileId,
            StorageFileUsageType usageType,
            StorageUsageOwnerType ownerType,
            Long ownerId,
            String ownerLabel,
            String snapshotUrl,
            Integer sortOrder,
            boolean protectedUsage
    ) {
        requireActiveAsset(fileId);
        insertUsageRow(fileId, usageType, ownerType, ownerId, ownerLabel, snapshotUrl, sortOrder, protectedUsage);
    }

    private void insertUsageRow(
            Long fileId,
            StorageFileUsageType usageType,
            StorageUsageOwnerType ownerType,
            Long ownerId,
            String ownerLabel,
            String snapshotUrl,
            Integer sortOrder,
            boolean protectedUsage
    ) {
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (asset_id, usage_type, owner_type, owner_id, owner_label, snapshot_url, sort_order, protected, status)
                        values
                            (:fileId, :usageType, :ownerType, :ownerId, :ownerLabel, :snapshotUrl, :sortOrder, :protectedUsage, 'ACTIVE')
                        """)
                .param("fileId", fileId)
                .param("usageType", usageType.name())
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .param("ownerLabel", ownerLabel == null ? "" : ownerLabel)
                .param("snapshotUrl", snapshotUrl == null ? "" : snapshotUrl)
                .param("sortOrder", sortOrder == null ? 0 : sortOrder)
                .param("protectedUsage", protectedUsage)
                .update();
    }

    private void updateUsage(Long usageId, String ownerLabel, UsageAssignment usage) {
        jdbcClient.sql("""
                        update storage_asset_usage
                        set owner_label = :ownerLabel,
                            snapshot_url = :snapshotUrl,
                            sort_order = :sortOrder,
                            protected = :protectedUsage,
                            updated_at = current_timestamp
                        where id = :usageId
                          and status = 'ACTIVE'
                        """)
                .param("ownerLabel", ownerLabel == null ? "" : ownerLabel)
                .param("snapshotUrl", usage.snapshotUrl() == null ? "" : usage.snapshotUrl())
                .param("sortOrder", usage.sortOrder() == null ? 0 : usage.sortOrder())
                .param("protectedUsage", usage.protectedUsage())
                .param("usageId", usageId)
                .update();
    }

    private void removeUsage(Long usageId) {
        jdbcClient.sql("""
                        update storage_asset_usage
                        set status = 'REMOVED',
                            updated_at = current_timestamp
                        where id = :usageId
                          and status = 'ACTIVE'
                        """)
                .param("usageId", usageId)
                .update();
    }

    private void requireActiveAsset(Long fileId) {
        jdbcClient.sql("""
                        select id
                        from storage_asset
                        where id = :fileId
                          and status = 'ACTIVE'
                        for update
                        """)
                .param("fileId", fileId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private StorageAssetUsageResponse mapUsage(ResultSet rs, int rowNum) throws SQLException {
        return new StorageAssetUsageResponse(
                rs.getLong("id"),
                rs.getLong("asset_id"),
                rs.getString("usage_type"),
                rs.getString("owner_type"),
                rs.getLong("owner_id"),
                rs.getString("owner_label"),
                rs.getString("snapshot_url"),
                rs.getInt("sort_order"),
                rs.getBoolean("protected"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    public record UsageAssignment(
            Long fileId,
            StorageFileUsageType usageType,
            String snapshotUrl,
            Integer sortOrder,
            boolean protectedUsage
    ) {
    }

    private record UsageKey(Long fileId, String usageType) {
        private static UsageKey from(UsageAssignment usage) {
            return new UsageKey(usage.fileId(), usage.usageType().name());
        }
    }

    private record ActiveUsageRow(Long id, Long fileId, String usageType) {
        private UsageKey key() {
            return new UsageKey(fileId, usageType);
        }
    }

    private record ActiveAssetRow(String scope, String mediaKind, String visibility, String status) {
    }
}
