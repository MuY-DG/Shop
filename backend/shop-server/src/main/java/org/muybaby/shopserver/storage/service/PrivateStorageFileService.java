package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageAssetScope;
import org.muybaby.shopserver.storage.StorageFileStatus;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.UploadedByType;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
public class PrivateStorageFileService {

    private static final Duration PAYMENT_SECRET_RELEASE_TTL = Duration.ofHours(24);

    private final JdbcClient jdbcClient;
    private final StorageProvider storageProvider;

    public PrivateStorageFileService(JdbcClient jdbcClient, StorageProvider storageProvider) {
        this.jdbcClient = jdbcClient;
        this.storageProvider = storageProvider;
    }

    public String readSecretText(Long assetId) {
        PrivateFileRow row = requireSecretRow(assetId, false);
        if (row.expiresAt() != null) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return readText(row);
    }

    /**
     * Reads payment secret objects without holding a database transaction or row lock. The returned
     * fingerprints must be revalidated with {@link #lockAndRevalidatePaymentSecrets(Collection, Collection)}
     * in the short transaction that persists the payment configuration.
     */
    public List<PaymentSecretSnapshot> inspectPaymentSecrets(Collection<Long> currentAssetIds) {
        Set<Long> current = normalizedIds(currentAssetIds);
        LocalDateTime now = databaseNow();
        List<PaymentSecretSnapshot> snapshots = new ArrayList<>(current.size());
        for (Long assetId : current) {
            PrivateFileRow row = requireSecretRow(assetId, false);
            validateCurrentPaymentSecret(assetId, row, now);
            byte[] content = readBytes(row);
            if (content.length != row.sizeBytes()) {
                throw unavailable();
            }
            String contentSha256 = sha256(content);
            String persistedSha256 = normalizeSha256(row.sha256());
            if (StringUtils.hasText(persistedSha256)
                    && !persistedSha256.equals(contentSha256)) {
                throw unavailable();
            }
            String text = new String(content, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(text)) {
                throw unavailable();
            }
            snapshots.add(new PaymentSecretSnapshot(
                    assetId,
                    persistedSha256,
                    contentSha256,
                    row.sizeBytes(),
                    row.objectLocation()
            ));
        }
        return List.copyOf(snapshots);
    }

    /**
     * Locks only database rows and compares them with content inspected outside the transaction.
     * No provider call is allowed on this path.
     */
    @Transactional
    public void lockAndRevalidatePaymentSecrets(
            Collection<PaymentSecretSnapshot> currentSnapshots,
            Collection<Long> previousAssetIds
    ) {
        Map<Long, PaymentSecretSnapshot> current = snapshotsByAssetId(currentSnapshots);
        Set<Long> all = new TreeSet<>(current.keySet());
        all.addAll(normalizedIds(previousAssetIds));
        LocalDateTime now = databaseNow();
        for (Long assetId : all) {
            PrivateFileRow row = requireSecretRow(assetId, true);
            PaymentSecretSnapshot snapshot = current.get(assetId);
            if (snapshot == null) {
                continue;
            }
            validateCurrentPaymentSecret(assetId, row, now);
            if (row.sizeBytes() != snapshot.sizeBytes()
                    || !Objects.equals(row.objectLocation(), snapshot.objectLocation())
                    || !Objects.equals(normalizeSha256(row.sha256()), snapshot.persistedSha256())) {
                throw unavailable();
            }
            if (!StringUtils.hasText(snapshot.persistedSha256())) {
                int updated = jdbcClient.sql("""
                                update storage_asset
                                set sha256 = :sha256,
                                    updated_at = current_timestamp
                                where id = :assetId
                                  and status = 'ACTIVE'
                                  and (sha256 is null or sha256 = '')
                                """)
                        .param("sha256", snapshot.contentSha256())
                        .param("assetId", assetId)
                        .update();
                if (updated != 1) {
                    throw unavailable();
                }
            } else if (!snapshot.persistedSha256().equals(snapshot.contentSha256())) {
                throw unavailable();
            }
        }
    }

    @Transactional
    public void reconcilePaymentSecretRetention(
            Collection<Long> currentAssetIds,
            Collection<Long> previousAssetIds
    ) {
        Set<Long> current = normalizedIds(currentAssetIds);
        Set<Long> released = new LinkedHashSet<>(normalizedIds(previousAssetIds));
        released.removeAll(current);

        for (Long assetId : current) {
            jdbcClient.sql("""
                            update storage_asset
                            set expires_at = null,
                                updated_at = current_timestamp
                            where id = :assetId
                              and scope = 'SECRET'
                              and status = 'ACTIVE'
                            """)
                    .param("assetId", assetId)
                    .update();
        }

        LocalDateTime releaseAt = databaseNow().plus(PAYMENT_SECRET_RELEASE_TTL);
        for (Long assetId : released) {
            jdbcClient.sql("""
                            update storage_asset
                            set expires_at = :releaseAt,
                                updated_at = current_timestamp
                            where id = :assetId
                              and scope = 'SECRET'
                              and status = 'ACTIVE'
                              and expires_at is null
                              and not exists (
                                  select 1
                                  from storage_asset_usage asset_usage
                                  where asset_usage.asset_id = storage_asset.id
                                    and asset_usage.status = 'ACTIVE'
                              )
                              and not exists (
                                  select 1
                                  from payment_config config
                                  where config.status = 'ACTIVE'
                                    and (config.private_key_file_id = storage_asset.id
                                      or config.merchant_certificate_file_id = storage_asset.id
                                      or config.wechat_public_key_file_id = storage_asset.id)
                              )
                            """)
                    .param("assetId", assetId)
                    .param("releaseAt", releaseAt)
                    .update();
        }
    }

    private PrivateFileRow requireSecretRow(Long assetId, boolean forUpdate) {
        if (assetId == null || assetId <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        PrivateFileRow row = jdbcClient.sql("""
                        select scope, media_kind, visibility, status, uploaded_by_type,
                               provider, storage_container, storage_region, object_key,
                               size_bytes, sha256, expires_at
                        from storage_asset
                        where id = :assetId
                        """ + (forUpdate ? " for update" : ""))
                .param("assetId", assetId)
                .query(this::mapPrivateFileRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        if (!StorageAssetScope.SECRET.name().equals(row.scope())
                || !StorageMediaKind.DOCUMENT.name().equals(row.mediaKind())
                || !FileVisibility.PRIVATE.name().equals(row.visibility())
                || !StorageFileStatus.ACTIVE.name().equals(row.status())
                || !UploadedByType.ADMIN.name().equals(row.uploadedByType())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return row;
    }

    private String readText(PrivateFileRow row) {
        return new String(readBytes(row), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(PrivateFileRow row) {
        try (InputStream inputStream = storageProvider.open(row.objectLocation()).inputStream()) {
            return inputStream.readAllBytes();
        } catch (IOException | RuntimeException ex) {
            throw unavailable();
        }
    }

    private void validateCurrentPaymentSecret(Long assetId, PrivateFileRow row, LocalDateTime now) {
        if (row.expiresAt() == null) {
            if (!hasActivePaymentConfigReference(assetId)) {
                throw unavailable();
            }
        } else if (!row.expiresAt().isAfter(now)) {
            throw unavailable();
        }
    }

    private boolean hasActivePaymentConfigReference(Long assetId) {
        Integer count = jdbcClient.sql("""
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
        return count != null && count > 0;
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private Set<Long> normalizedIds(Collection<Long> assetIds) {
        Set<Long> normalized = new TreeSet<>();
        if (assetIds == null) {
            return normalized;
        }
        for (Long assetId : assetIds) {
            if (assetId != null) {
                normalized.add(assetId);
            }
        }
        return normalized;
    }

    private Map<Long, PaymentSecretSnapshot> snapshotsByAssetId(
            Collection<PaymentSecretSnapshot> snapshots
    ) {
        Map<Long, PaymentSecretSnapshot> normalized = new LinkedHashMap<>();
        if (snapshots == null) {
            return normalized;
        }
        for (PaymentSecretSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.assetId() == null || snapshot.assetId() <= 0
                    || normalized.putIfAbsent(snapshot.assetId(), snapshot) != null) {
                throw unavailable();
            }
        }
        return normalized;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String normalizeSha256(String sha256) {
        return sha256 == null ? "" : sha256.trim().toLowerCase();
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
    }

    private PrivateFileRow mapPrivateFileRow(ResultSet rs, int rowNum) throws SQLException {
        return new PrivateFileRow(
                rs.getString("scope"),
                rs.getString("media_kind"),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getString("uploaded_by_type"),
                rs.getString("provider"),
                rs.getString("storage_container"),
                rs.getString("storage_region"),
                rs.getString("object_key"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getObject("expires_at", LocalDateTime.class)
        );
    }

    private record PrivateFileRow(
            String scope,
            String mediaKind,
            String visibility,
            String status,
            String uploadedByType,
            String provider,
            String storageContainer,
            String storageRegion,
            String objectKey,
            long sizeBytes,
            String sha256,
            LocalDateTime expiresAt
    ) {
        private StorageObjectLocation objectLocation() {
            return new StorageObjectLocation(
                    StorageProviderKind.valueOf(provider),
                    storageContainer,
                    storageRegion,
                    objectKey
            );
        }
    }

    public record PaymentSecretSnapshot(
            Long assetId,
            String persistedSha256,
            String contentSha256,
            long sizeBytes,
            StorageObjectLocation objectLocation
    ) {
    }
}
