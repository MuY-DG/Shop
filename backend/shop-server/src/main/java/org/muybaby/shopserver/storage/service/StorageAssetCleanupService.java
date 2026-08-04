package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Service
public class StorageAssetCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StorageAssetCleanupService.class);
    private static final Duration CLEANUP_LEASE = Duration.ofMinutes(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final JdbcClient jdbcClient;
    private final StorageProvider storageProvider;
    private final TransactionTemplate transactionTemplate;

    public StorageAssetCleanupService(
            JdbcClient jdbcClient,
            StorageProvider storageProvider,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.storageProvider = storageProvider;
        this.transactionTemplate = transactionTemplate;
    }

    public CleanupBatchResult cleanupExpiredAssets(int batchSize, Duration uploadPendingGrace) {
        return cleanupExpiredAssets(batchSize, uploadPendingGrace, 0L);
    }

    public CleanupBatchResult cleanupExpiredAssets(
            int batchSize,
            Duration uploadPendingGrace,
            long runSequence
    ) {
        return cleanupExpiredAssets(batchSize, uploadPendingGrace, runSequence, () -> true);
    }

    public CleanupBatchResult cleanupExpiredAssets(
            int batchSize,
            Duration uploadPendingGrace,
            long runSequence,
            BooleanSupplier leaseActive
    ) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("Invalid storage cleanup batch size");
        }
        if (leaseActive == null) {
            throw new IllegalArgumentException("Storage cleanup lease state is required");
        }
        Duration effectiveGrace = positiveDuration(uploadPendingGrace, Duration.ofMinutes(30));
        Set<Long> candidateIds = new LinkedHashSet<>();
        int firstCategory = rotationOffset(runSequence, CleanupCandidateCategory.values().length);
        for (int categoryOffset = 0;
                categoryOffset < CleanupCandidateCategory.values().length
                        && remaining(batchSize, candidateIds) > 0
                        && leaseActive.getAsBoolean();
                categoryOffset++) {
            CleanupCandidateCategory category = CleanupCandidateCategory.values()[
                    (firstCategory + categoryOffset) % CleanupCandidateCategory.values().length];
            List<Long> candidates = switch (category) {
                case EXPIRED -> expiredCandidateIds(remaining(batchSize, candidateIds));
                case STALE_UPLOAD -> staleUploadCandidateIds(
                        remaining(batchSize, candidateIds), effectiveGrace);
                case ORPHANED_USER_AVATAR -> orphanedUserAvatarCandidateIds(
                        remaining(batchSize, candidateIds), effectiveGrace);
                case RETRY -> retryCandidateIds(remaining(batchSize, candidateIds));
            };
            addCandidates(candidateIds, candidates, batchSize);
        }

        int attempted = 0;
        int cleaned = 0;
        for (Long assetId : candidateIds) {
            if (!leaseActive.getAsBoolean()) {
                break;
            }
            attempted++;
            if (cleanupAsset(assetId, effectiveGrace)) {
                cleaned++;
            }
        }
        return new CleanupBatchResult(attempted, cleaned);
    }

    private int rotationOffset(long runSequence, int categoryCount) {
        return runSequence <= 0
                ? 0
                : Math.floorMod(runSequence - 1, categoryCount);
    }

    /**
     * Claims and physically removes one pending asset. The provider call deliberately runs between
     * the short claim and finalize transactions so callers can reuse the same lease/retry protocol.
     */
    public boolean cleanupAsset(Long assetId) {
        return cleanupAsset(assetId, Duration.ofMinutes(30));
    }

    private boolean cleanupAsset(Long assetId, Duration uploadPendingGrace) {
        CleanupAsset asset = transactionTemplate.execute(status -> claim(assetId, uploadPendingGrace));
        if (asset == null) {
            return false;
        }
        try {
            storageProvider.delete(asset.objectLocation());
            if (asset.thumbnailLocation() != null) {
                storageProvider.delete(asset.thumbnailLocation());
            }
        } catch (RuntimeException ex) {
            RetrySchedule retry = transactionTemplate.execute(status -> scheduleRetry(asset));
            if (retry != null && retry.scheduled()) {
                log.warn(
                        "Storage asset cleanup failed; scheduled retry: assetId={}, provider={}, attempts={}, retryAt={}, exception={}",
                        asset.id(), asset.provider(), retry.attempts(), retry.retryAt(),
                        ex.getClass().getSimpleName()
                );
            } else {
                log.warn(
                        "Storage asset cleanup failed after its lease was lost: assetId={}, provider={}, exception={}",
                        asset.id(), asset.provider(), ex.getClass().getSimpleName()
                );
            }
            return false;
        }
        Boolean finalized = transactionTemplate.execute(status -> finalizeDelete(asset));
        return Boolean.TRUE.equals(finalized);
    }

    private List<Long> expiredCandidateIds(int limit) {
        if (limit < 1) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select asset.id
                        from storage_asset asset
                        where asset.scope in ('LIBRARY', 'ATTACHMENT', 'SECRET')
                          and asset.status = 'ACTIVE'
                          and asset.expires_at is not null
                          and asset.expires_at <= current_timestamp
                          and not exists (
                              select 1
                              from storage_asset_usage asset_usage
                              where asset_usage.asset_id = asset.id
                                and asset_usage.status = 'ACTIVE'
                          )
                          and not exists (
                              select 1
                              from payment_config config
                              where config.status = 'ACTIVE'
                                and (config.private_key_file_id = asset.id
                                  or config.merchant_certificate_file_id = asset.id
                                  or config.wechat_public_key_file_id = asset.id)
                          )
                        order by asset.expires_at asc, asset.id asc
                        limit :limit
                        """)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    private List<Long> retryCandidateIds(int limit) {
        if (limit < 1) {
            return List.of();
        }
        return jdbcClient.sql("""
                        select asset.id
                        from storage_asset asset
                        where asset.status = 'DELETE_PENDING'
                          and (asset.cleanup_next_retry_at is null
                            or asset.cleanup_next_retry_at <= current_timestamp)
                        order by asset.cleanup_next_retry_at asc, asset.id asc
                        limit :limit
                        """)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    private List<Long> staleUploadCandidateIds(int limit, Duration uploadPendingGrace) {
        if (limit < 1) {
            return List.of();
        }
        LocalDateTime staleBefore = databaseNow().minus(uploadPendingGrace);
        return jdbcClient.sql("""
                        select asset.id
                        from storage_asset asset
                        where asset.status = 'UPLOAD_PENDING'
                          and asset.created_at <= :staleBefore
                          and (asset.cleanup_next_retry_at is null
                            or asset.cleanup_next_retry_at <= current_timestamp)
                        order by asset.created_at asc, asset.id asc
                        limit :limit
                        """)
                .param("staleBefore", staleBefore)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    private List<Long> orphanedUserAvatarCandidateIds(int limit, Duration uploadPendingGrace) {
        if (limit < 1) {
            return List.of();
        }
        LocalDateTime staleBefore = databaseNow().minus(uploadPendingGrace);
        return jdbcClient.sql("""
                        select asset.id
                        from storage_asset asset
                        where asset.scope = 'LIBRARY'
                          and asset.media_kind = 'IMAGE'
                          and asset.status = 'ACTIVE'
                          and asset.uploaded_by_type = 'APP'
                          and asset.upload_context_type = 'APP_USER_AVATAR'
                          and asset.created_at <= :staleBefore
                          and not exists (
                              select 1
                              from app_user app_user_reference
                              where app_user_reference.avatar_url = asset.public_url
                          )
                        order by asset.created_at asc, asset.id asc
                        limit :limit
                        """)
                .param("staleBefore", staleBefore)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    private CleanupAsset claim(Long assetId, Duration uploadPendingGrace) {
        CleanupAsset asset = jdbcClient.sql("""
                        select id, scope, status, expires_at, cleanup_attempts, created_at,
                               cleanup_next_retry_at, provider,
                               storage_container, storage_region, object_key,
                               thumbnail_object_key
                        from storage_asset
                        where id = :assetId
                        for update
                        """)
                .param("assetId", assetId)
                .query(this::mapCleanupAsset)
                .optional()
                .orElse(null);
        if (asset == null) {
            return null;
        }
        LocalDateTime now = databaseNow();
        LocalDateTime leaseUntil = now.plus(CLEANUP_LEASE);
        String leaseToken = UUID.randomUUID().toString();
        if ("DELETE_PENDING".equals(asset.status())) {
            if (asset.cleanupNextRetryAt() != null && asset.cleanupNextRetryAt().isAfter(now)) {
                return null;
            }
            int leased = jdbcClient.sql("""
                            update storage_asset
                            set cleanup_next_retry_at = :leaseUntil,
                                cleanup_lease_token = :leaseToken,
                                updated_at = current_timestamp
                            where id = :assetId
                              and status = 'DELETE_PENDING'
                              and (cleanup_next_retry_at is null
                                or cleanup_next_retry_at <= current_timestamp)
                            """)
                    .param("leaseUntil", leaseUntil)
                    .param("leaseToken", leaseToken)
                    .param("assetId", asset.id())
                    .update();
            return leased == 1 ? asset.withLeaseToken(leaseToken) : null;
        }

        if ("UPLOAD_PENDING".equals(asset.status())) {
            LocalDateTime staleBefore = now.minus(uploadPendingGrace);
            if (asset.createdAt().isAfter(staleBefore)
                    || (asset.cleanupNextRetryAt() != null && asset.cleanupNextRetryAt().isAfter(now))) {
                return null;
            }
            int leased = jdbcClient.sql("""
                            update storage_asset
                            set status = 'DELETE_PENDING',
                                public_url = null,
                                cleanup_attempts = 0,
                                cleanup_next_retry_at = :leaseUntil,
                                cleanup_lease_token = :leaseToken,
                                updated_at = current_timestamp
                            where id = :assetId
                              and status = 'UPLOAD_PENDING'
                              and created_at <= :staleBefore
                              and (cleanup_next_retry_at is null
                                or cleanup_next_retry_at <= current_timestamp)
                            """)
                    .param("assetId", asset.id())
                    .param("staleBefore", staleBefore)
                    .param("leaseUntil", leaseUntil)
                    .param("leaseToken", leaseToken)
                    .update();
            return leased == 1 ? asset.withLeaseToken(leaseToken) : null;
        }

        LocalDateTime staleBefore = now.minus(uploadPendingGrace);
        int orphanedAvatarLeased = jdbcClient.sql("""
                        update storage_asset
                        set status = 'DELETE_PENDING',
                            folder_id = null,
                            public_url = null,
                            cleanup_attempts = 0,
                            cleanup_next_retry_at = :leaseUntil,
                            cleanup_lease_token = :leaseToken,
                            updated_at = current_timestamp
                        where id = :assetId
                          and scope = 'LIBRARY'
                          and media_kind = 'IMAGE'
                          and status = 'ACTIVE'
                          and uploaded_by_type = 'APP'
                          and upload_context_type = 'APP_USER_AVATAR'
                          and created_at <= :staleBefore
                          and not exists (
                              select 1
                              from app_user app_user_reference
                              where app_user_reference.avatar_url = storage_asset.public_url
                          )
                        """)
                .param("assetId", asset.id())
                .param("staleBefore", staleBefore)
                .param("leaseUntil", leaseUntil)
                .param("leaseToken", leaseToken)
                .update();
        if (orphanedAvatarLeased == 1) {
            return asset.withLeaseToken(leaseToken);
        }

        if (!isExpirableScope(asset.scope())
                || !"ACTIVE".equals(asset.status())
                || asset.expiresAt() == null
                || asset.expiresAt().isAfter(now)
                || hasActiveReference(asset.id())) {
            return null;
        }
        int updated = jdbcClient.sql("""
                        update storage_asset
                        set status = 'DELETE_PENDING',
                            public_url = null,
                            cleanup_attempts = 0,
                            cleanup_next_retry_at = :leaseUntil,
                            cleanup_lease_token = :leaseToken,
                            updated_at = current_timestamp
                        where id = :assetId
                          and status = 'ACTIVE'
                          and expires_at is not null
                          and expires_at <= current_timestamp
                        """)
                .param("assetId", asset.id())
                .param("leaseUntil", leaseUntil)
                .param("leaseToken", leaseToken)
                .update();
        return updated == 1 ? asset.withLeaseToken(leaseToken) : null;
    }

    private RetrySchedule scheduleRetry(CleanupAsset asset) {
        int nextAttempts = asset.cleanupAttempts() + 1;
        long multiplier = 1L << Math.min(nextAttempts - 1, 6);
        Duration delay = Duration.ofMinutes(multiplier);
        if (delay.compareTo(MAX_RETRY_DELAY) > 0) {
            delay = MAX_RETRY_DELAY;
        }
        LocalDateTime retryAt = databaseNow().plus(delay);
        int updated = jdbcClient.sql("""
                        update storage_asset
                        set cleanup_attempts = :attempts,
                            cleanup_next_retry_at = :retryAt,
                            cleanup_lease_token = null,
                            updated_at = current_timestamp
                        where id = :assetId
                          and status = 'DELETE_PENDING'
                          and cleanup_lease_token = :leaseToken
                        """)
                .param("attempts", nextAttempts)
                .param("retryAt", retryAt)
                .param("assetId", asset.id())
                .param("leaseToken", asset.leaseToken())
                .update();
        return new RetrySchedule(updated == 1, nextAttempts, retryAt);
    }

    private boolean finalizeDelete(CleanupAsset asset) {
        int updated = jdbcClient.sql("""
                        update storage_asset
                        set status = 'DELETED',
                            folder_id = null,
                            public_url = null,
                            expires_at = null,
                            thumbnail_status = 'NONE',
                            thumbnail_object_key = null,
                            thumbnail_content_type = null,
                            thumbnail_size_bytes = null,
                            thumbnail_sha256 = null,
                            thumbnail_object_etag = null,
                            thumbnail_width = null,
                            thumbnail_height = null,
                            thumbnail_started_at = null,
                            thumbnail_next_retry_at = null,
                            cleanup_next_retry_at = null,
                            cleanup_lease_token = null,
                            deleted_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :assetId
                          and status = 'DELETE_PENDING'
                          and cleanup_lease_token = :leaseToken
                        """)
                .param("assetId", asset.id())
                .param("leaseToken", asset.leaseToken())
                .update();
        return updated == 1;
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private boolean hasActiveReference(Long assetId) {
        Integer usageCount = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :assetId
                          and status = 'ACTIVE'
                        """)
                .param("assetId", assetId)
                .query(Integer.class)
                .single();
        if (usageCount != null && usageCount > 0) {
            return true;
        }
        Integer paymentCount = jdbcClient.sql("""
                        select count(*)
                        from payment_config
                        where status = 'ACTIVE'
                          and (private_key_file_id = :assetId
                            or merchant_certificate_file_id = :assetId
                            or wechat_public_key_file_id = :assetId)
                        """)
                .param("assetId", assetId)
                .query(Integer.class)
                .single();
        return paymentCount != null && paymentCount > 0;
    }

    private CleanupAsset mapCleanupAsset(ResultSet rs, int rowNum) throws SQLException {
        return new CleanupAsset(
                rs.getLong("id"),
                rs.getString("scope"),
                rs.getString("status"),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getInt("cleanup_attempts"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("cleanup_next_retry_at", LocalDateTime.class),
                rs.getString("provider"),
                rs.getString("storage_container"),
                rs.getString("storage_region"),
                rs.getString("object_key"),
                rs.getString("thumbnail_object_key"),
                null
        );
    }

    private record CleanupAsset(
            Long id,
            String scope,
            String status,
            LocalDateTime expiresAt,
            int cleanupAttempts,
            LocalDateTime createdAt,
            LocalDateTime cleanupNextRetryAt,
            String provider,
            String storageContainer,
            String storageRegion,
            String objectKey,
            String thumbnailObjectKey,
            String leaseToken
    ) {
        private CleanupAsset withLeaseToken(String token) {
            return new CleanupAsset(
                    id, scope, status, expiresAt, cleanupAttempts, createdAt, cleanupNextRetryAt,
                    provider, storageContainer, storageRegion, objectKey, thumbnailObjectKey, token
            );
        }

        private StorageObjectLocation objectLocation() {
            return new StorageObjectLocation(
                    StorageProviderKind.valueOf(provider),
                    storageContainer,
                    storageRegion,
                    objectKey
            );
        }

        private StorageObjectLocation thumbnailLocation() {
            if (thumbnailObjectKey == null || thumbnailObjectKey.isBlank()) {
                return null;
            }
            return new StorageObjectLocation(
                    StorageProviderKind.valueOf(provider),
                    storageContainer,
                    storageRegion,
                    thumbnailObjectKey
            );
        }
    }

    private record RetrySchedule(boolean scheduled, int attempts, LocalDateTime retryAt) {
    }

    public record CleanupBatchResult(int attemptedCount, int cleanedCount) {
    }

    private enum CleanupCandidateCategory {
        EXPIRED,
        STALE_UPLOAD,
        ORPHANED_USER_AVATAR,
        RETRY
    }

    private boolean isExpirableScope(String scope) {
        return "LIBRARY".equals(scope)
                || "ATTACHMENT".equals(scope)
                || "SECRET".equals(scope);
    }

    private Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private int remaining(int batchSize, Set<Long> candidateIds) {
        return Math.max(0, batchSize - candidateIds.size());
    }

    private void addCandidates(Set<Long> target, List<Long> candidates, int batchSize) {
        for (Long candidate : candidates) {
            if (target.size() >= batchSize) {
                return;
            }
            target.add(candidate);
        }
    }
}
