package org.muybaby.shopserver.finance.reconciliation.storage;

import org.muybaby.shopserver.finance.reconciliation.download.StagedTradeBill;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FinanceTradeBillStorage {

    public static final String CONTENT_TYPE = "text/csv; charset=UTF-8";

    private final StorageProvider storageProvider;
    private final StorageRuntimeConfigService storageConfigService;

    public FinanceTradeBillStorage(
            StorageProvider storageProvider,
            StorageRuntimeConfigService storageConfigService
    ) {
        this.storageProvider = storageProvider;
        this.storageConfigService = storageConfigService;
    }

    public StoredTradeBillSource store(
            String mchId,
            LocalDate billDate,
            StagedTradeBill staged
    ) throws IOException {
        ResolvedStorageConfig config = storageConfigService.effective();
        StorageObjectLocation location = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                config.bucket(),
                config.region(),
                objectKey(mchId, billDate)
        );
        try (InputStream input = new BufferedInputStream(Files.newInputStream(staged.path()))) {
            storageProvider.put(location, CONTENT_TYPE, input, staged.sizeBytes());
        }
        return new StoredTradeBillSource(
                location,
                CONTENT_TYPE,
                staged.sizeBytes(),
                staged.contentSha256()
        );
    }

    public StoredObject open(StorageObjectLocation location) {
        return storageProvider.open(location);
    }

    public void deleteQuietly(StorageObjectLocation location) {
        if (location == null) {
            return;
        }
        try {
            storageProvider.delete(location);
        } catch (RuntimeException ignored) {
            // A failed cleanup leaves a private orphan; it must not mask reconciliation evidence.
        }
    }

    private String objectKey(String mchId, LocalDate billDate) {
        String digest = sha256(mchId);
        return "private/finance/wechat-trade-bill/v1/"
                + digest.substring(0, 2)
                + "/"
                + digest
                + "/"
                + billDate
                + "/"
                + UUID.randomUUID()
                + ".csv";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
