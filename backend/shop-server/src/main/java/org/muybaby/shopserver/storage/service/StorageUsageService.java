package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StoragePurpose;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.dto.StorageFileUsageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class StorageUsageService {

    private final JdbcClient jdbcClient;

    public StorageUsageService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<StorageFileUsageResponse> usages(Long fileId) {
        return jdbcClient.sql("""
                        select id, file_id, usage_type, owner_type, owner_id, owner_label, snapshot_url,
                               sort_order, protected, status, created_at, updated_at
                        from storage_file_usage
                        where file_id = :fileId
                        order by status asc, sort_order asc, id asc
                        """)
                .param("fileId", fileId)
                .query(this::mapUsage)
                .list();
    }

    public boolean hasActiveUsages(Long fileId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where file_id = :fileId
                          and status = 'ACTIVE'
                        """)
                .param("fileId", fileId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    @Transactional
    public void requireActivePublicMedia(Long fileId, StorageMediaKind mediaKind) {
        ActiveFileRow file = jdbcClient.sql("""
                        select purpose, visibility, status
                        from storage_file
                        where id = :fileId
                        for update
                        """)
                .param("fileId", fileId)
                .query((rs, rowNum) -> new ActiveFileRow(
                        rs.getString("purpose"),
                        rs.getString("visibility"),
                        rs.getString("status")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        try {
            StoragePurpose purpose = StoragePurpose.valueOf(file.purpose());
            if (!"ACTIVE".equals(file.status())
                    || !"PUBLIC".equals(file.visibility())
                    || purpose.mediaKind() != mediaKind) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
        } catch (IllegalArgumentException ex) {
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
        jdbcClient.sql("""
                        update storage_file_usage
                        set status = 'REMOVED',
                            updated_at = current_timestamp
                        where owner_type = :ownerType
                          and owner_id = :ownerId
                          and status = 'ACTIVE'
                        """)
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .update();

        for (UsageAssignment usage : usages) {
            insertUsage(usage.fileId(), usage.usageType(), ownerType, ownerId, ownerLabel, usage.snapshotUrl(), usage.sortOrder(), usage.protectedUsage());
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
                        update storage_file_usage
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
        boolean activeFileExists = jdbcClient.sql("""
                        select id
                        from storage_file
                        where id = :fileId
                          and status = 'ACTIVE'
                          and visibility = 'PUBLIC'
                        for update
                        """)
                .param("fileId", usage.fileId())
                .query(Long.class)
                .optional()
                .isPresent();
        if (!activeFileExists) {
            return false;
        }

        Integer activeUsageCount = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where file_id = :fileId
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
        requireActiveFile(fileId);
        jdbcClient.sql("""
                        insert into storage_file_usage
                            (file_id, usage_type, owner_type, owner_id, owner_label, snapshot_url, sort_order, protected, status)
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

    private void requireActiveFile(Long fileId) {
        jdbcClient.sql("""
                        select id
                        from storage_file
                        where id = :fileId
                          and status = 'ACTIVE'
                        for update
                        """)
                .param("fileId", fileId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private StorageFileUsageResponse mapUsage(ResultSet rs, int rowNum) throws SQLException {
        return new StorageFileUsageResponse(
                rs.getLong("id"),
                rs.getLong("file_id"),
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

    private record ActiveFileRow(String purpose, String visibility, String status) {
    }
}
