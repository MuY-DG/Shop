package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentSecretEncryptionProperties;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Rewraps persisted application secrets with the active v2 key in bounded, independently
 * committed units. Every write uses the previously observed row revision as a compare-and-set
 * guard, so a concurrent administrator update or another node's rotation is never overwritten.
 */
@Service
public class PaymentSecretRotationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSecretRotationService.class);
    private static final int MAX_BATCH_SIZE = 200;
    private static final String PAYMENT_CONFIG_CHECKPOINT = "payment-config";
    private static final String SNAPSHOT_CHECKPOINT = "payment-config-snapshot";
    private static final String STORAGE_CHECKPOINT = "storage-runtime-setting";
    private static final String NUMERIC_CURSOR_START = "0";
    private static final String TEXT_CURSOR_START = "";

    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;
    private final PaymentConfigSnapshotStore snapshotStore;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentSecretEncryptionProperties properties;
    private final TransactionTemplate requiresNewTransaction;

    public PaymentSecretRotationService(
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher,
            PaymentConfigSnapshotStore snapshotStore,
            PaymentConfigResolver paymentConfigResolver,
            PaymentSecretEncryptionProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
        this.snapshotStore = snapshotStore;
        this.paymentConfigResolver = paymentConfigResolver;
        this.properties = properties;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Claims at most one configured batch from each secret domain through a durable checkpoint,
     * then rotates every claimed row in its own transaction. The claim commits before row work, so
     * damaged rows and process restarts cannot hold the cursor or starve later rows.
     */
    public synchronized int rotateBatch() {
        if (properties.effectiveWriteVersion() != 2) {
            return 0;
        }
        int batchSize = properties.effectiveRotationBatchSize();
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalStateException("Payment secret rotation batch size must be between 1 and 200");
        }
        String targetKeyId = normalizeKeyId(properties.activeKeyId());
        if (!StringUtils.hasText(targetKeyId)) {
            throw new IllegalStateException("Payment secret rotation requires an active key id");
        }

        int rotated = 0;
        for (PaymentConfigEnvelope candidate : paymentConfigCandidates(batchSize)) {
            rotated += rotateSafely(
                    PAYMENT_CONFIG_CHECKPOINT, Long.toString(candidate.id()), candidate.keyId(),
                    () -> rotatePaymentConfig(candidate));
        }
        for (SnapshotEnvelope candidate : snapshotCandidates(batchSize)) {
            rotated += rotateSafely(
                    SNAPSHOT_CHECKPOINT, candidate.fingerprint(), candidate.keyId(),
                    () -> rotateSnapshot(candidate));
        }
        for (StorageEnvelope candidate : storageCandidates(batchSize)) {
            rotated += rotateSafely(
                    STORAGE_CHECKPOINT, Long.toString(candidate.id()), candidate.keyId(),
                    () -> rotateStorage(candidate));
        }
        return rotated;
    }

    private int rotateSafely(String domain, String rowIdentity, String oldKeyId, IntSupplier action) {
        try {
            Integer result = requiresNewTransaction.execute(status -> action.getAsInt());
            return result == null ? 0 : result;
        } catch (RuntimeException ex) {
            log.warn(
                    "Secret rotation row failed and will be retried (domain={}, row={}, keyId={}, type={})",
                    domain, rowIdentity, normalizeKeyId(oldKeyId), ex.getClass().getSimpleName());
            return 0;
        }
    }

    private <T> List<T> claimCandidates(
            String checkpointName,
            String initialCursor,
            Function<String, List<T>> queryAfterCursor,
            Function<T, String> cursorValue
    ) {
        List<T> claimed = requiresNewTransaction.execute(status -> {
            String currentCursor = lockCheckpoint(checkpointName);
            List<T> candidates = queryAfterCursor.apply(currentCursor);
            boolean wrapped = candidates.isEmpty() && !currentCursor.equals(initialCursor);
            if (wrapped) {
                candidates = queryAfterCursor.apply(initialCursor);
            }
            String claimedThrough = candidates.isEmpty()
                    ? initialCursor
                    : cursorValue.apply(candidates.get(candidates.size() - 1));
            advanceCheckpoint(checkpointName, claimedThrough, wrapped);
            return List.copyOf(candidates);
        });
        return Objects.requireNonNull(claimed);
    }

    private String lockCheckpoint(String checkpointName) {
        return jdbcClient.sql("""
                        select cursor_value
                        from payment_secret_rotation_checkpoint
                        where checkpoint_name = :checkpointName
                        for update
                        """)
                .param("checkpointName", checkpointName)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing payment secret rotation checkpoint: " + checkpointName));
    }

    private void advanceCheckpoint(String checkpointName, String cursorValue, boolean wrapped) {
        int updatedRows = jdbcClient.sql("""
                        update payment_secret_rotation_checkpoint
                        set cursor_value = :cursorValue,
                            scan_epoch = scan_epoch + :epochIncrement,
                            updated_at = current_timestamp
                        where checkpoint_name = :checkpointName
                        """)
                .param("cursorValue", cursorValue)
                .param("epochIncrement", wrapped ? 1 : 0)
                .param("checkpointName", checkpointName)
                .update();
        if (updatedRows != 1) {
            throw new IllegalStateException(
                    "Payment secret rotation checkpoint update failed: " + checkpointName);
        }
    }

    private long parseNumericCursor(String cursorValue) {
        try {
            long parsed = Long.parseLong(cursorValue);
            if (parsed < 0) {
                throw new NumberFormatException("negative cursor");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid payment secret rotation checkpoint", ex);
        }
    }

    private List<PaymentConfigEnvelope> paymentConfigCandidates(int batchSize) {
        return claimCandidates(
                PAYMENT_CONFIG_CHECKPOINT,
                NUMERIC_CURSOR_START,
                cursor -> queryPaymentConfigCandidates(parseNumericCursor(cursor), batchSize),
                candidate -> Long.toString(candidate.id())
        );
    }

    private List<PaymentConfigEnvelope> queryPaymentConfigCandidates(long afterId, int batchSize) {
        return jdbcClient.sql("""
                        select id, api_v3_key_ciphertext, secret_cipher_version, secret_key_id,
                               secret_revision
                        from payment_config
                        where id > :afterId
                        order by id
                        limit :batchSize
                        """)
                .param("afterId", afterId)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new PaymentConfigEnvelope(
                        rs.getLong("id"),
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id"),
                        rs.getLong("secret_revision")))
                .list();
    }

    private int rotatePaymentConfig(PaymentConfigEnvelope candidate) {
        if (!secretCipher.shouldReencrypt(candidate.version(), candidate.keyId())) {
            return 0;
        }
        PaymentSecretCipher.SecretContext context =
                PaymentConfigResolver.apiV3KeyContext(candidate.id());
        PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(
                context, candidate.ciphertext());
        requireMetadata(decrypted, candidate.version(), candidate.keyId());
        if (!secretCipher.shouldReencrypt(decrypted.version(), decrypted.keyId())) {
            return 0;
        }
        PaymentSecretCipher.EncryptedSecret encrypted = secretCipher.encrypt(
                context, decrypted.plaintext());
        requireTargetEnvelope(encrypted);
        return jdbcClient.sql("""
                        update payment_config
                        set api_v3_key_ciphertext = :newCiphertext,
                            secret_cipher_version = :newVersion,
                            secret_key_id = :newKeyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :id
                          and secret_revision = :oldRevision
                        """)
                .param("newCiphertext", encrypted.ciphertext())
                .param("newVersion", encrypted.version())
                .param("newKeyId", encrypted.keyId())
                .param("id", candidate.id())
                .param("oldRevision", candidate.revision())
                .update();
    }

    private List<SnapshotEnvelope> snapshotCandidates(int batchSize) {
        return claimCandidates(
                SNAPSHOT_CHECKPOINT,
                TEXT_CURSOR_START,
                cursor -> querySnapshotCandidates(cursor, batchSize),
                SnapshotEnvelope::fingerprint
        );
    }

    private List<SnapshotEnvelope> querySnapshotCandidates(String afterFingerprint, int batchSize) {
        return jdbcClient.sql("""
                        select fingerprint, api_v3_key_ciphertext, private_key_pem_ciphertext,
                               wechat_public_key_pem_ciphertext, secret_cipher_version, secret_key_id,
                               secret_revision
                        from payment_config_snapshot
                        where fingerprint > :afterFingerprint
                        order by fingerprint
                        limit :batchSize
                        """)
                .param("afterFingerprint", afterFingerprint)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new SnapshotEnvelope(
                        rs.getString("fingerprint"),
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getString("private_key_pem_ciphertext"),
                        rs.getString("wechat_public_key_pem_ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id"),
                        rs.getLong("secret_revision")))
                .list();
    }

    private int rotateSnapshot(SnapshotEnvelope candidate) {
        if (!secretCipher.shouldReencrypt(candidate.version(), candidate.keyId())) {
            return 0;
        }
        ResolvedPaymentConfig config = snapshotStore.findEnvironmentConfig(candidate.fingerprint())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED));
        if (!constantTimeEquals(candidate.fingerprint(), paymentConfigResolver.fingerprint(config))) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        if (!secretCipher.shouldReencrypt(candidate.version(), candidate.keyId())) {
            return 0;
        }

        PaymentSecretCipher.EncryptedSecret apiV3Key = secretCipher.encrypt(
                PaymentConfigSnapshotStore.secretContext(candidate.fingerprint(), "api-v3-key"),
                config.apiV3Key());
        PaymentSecretCipher.EncryptedSecret privateKeyPem = secretCipher.encrypt(
                PaymentConfigSnapshotStore.secretContext(candidate.fingerprint(), "private-key-pem"),
                config.privateKeyPem());
        PaymentSecretCipher.EncryptedSecret publicKeyPem = secretCipher.encrypt(
                PaymentConfigSnapshotStore.secretContext(
                        candidate.fingerprint(), "wechat-public-key-pem"),
                config.wechatPublicKeyPem());
        requireSameTargetEnvelope(apiV3Key, privateKeyPem, publicKeyPem);

        return jdbcClient.sql("""
                        update payment_config_snapshot
                        set api_v3_key_ciphertext = :newApiV3Key,
                            private_key_pem_ciphertext = :newPrivateKeyPem,
                            wechat_public_key_pem_ciphertext = :newPublicKeyPem,
                            secret_cipher_version = :newVersion,
                            secret_key_id = :newKeyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = current_timestamp
                        where fingerprint = :fingerprint
                          and secret_revision = :oldRevision
                        """)
                .param("newApiV3Key", apiV3Key.ciphertext())
                .param("newPrivateKeyPem", privateKeyPem.ciphertext())
                .param("newPublicKeyPem", publicKeyPem.ciphertext())
                .param("newVersion", apiV3Key.version())
                .param("newKeyId", apiV3Key.keyId())
                .param("fingerprint", candidate.fingerprint())
                .param("oldRevision", candidate.revision())
                .update();
    }

    private List<StorageEnvelope> storageCandidates(int batchSize) {
        return claimCandidates(
                STORAGE_CHECKPOINT,
                NUMERIC_CURSOR_START,
                cursor -> queryStorageCandidates(parseNumericCursor(cursor), batchSize),
                candidate -> Long.toString(candidate.id())
        );
    }

    private List<StorageEnvelope> queryStorageCandidates(long afterId, int batchSize) {
        return jdbcClient.sql("""
                        select id, cos_secret_id_ciphertext, cos_secret_key_ciphertext,
                               secret_cipher_version, secret_key_id, secret_revision
                        from storage_runtime_setting
                        where id > :afterId
                          and (cos_secret_id_ciphertext <> '' or cos_secret_key_ciphertext <> '')
                        order by id
                        limit :batchSize
                        """)
                .param("afterId", afterId)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new StorageEnvelope(
                        rs.getLong("id"),
                        rs.getString("cos_secret_id_ciphertext"),
                        rs.getString("cos_secret_key_ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id"),
                        rs.getLong("secret_revision")))
                .list();
    }

    private int rotateStorage(StorageEnvelope candidate) {
        if (candidate.id() != 1L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!secretCipher.shouldReencrypt(candidate.version(), candidate.keyId())) {
            return 0;
        }
        String secretId = decryptOptionalStorageSecret(
                "cos-secret-id", candidate.secretIdCiphertext(), candidate);
        String secretKey = decryptOptionalStorageSecret(
                "cos-secret-key", candidate.secretKeyCiphertext(), candidate);
        if (!secretCipher.shouldReencrypt(candidate.version(), candidate.keyId())) {
            return 0;
        }
        PaymentSecretCipher.EncryptedSecret encryptedId = encryptOptionalStorageSecret(
                "cos-secret-id", secretId);
        PaymentSecretCipher.EncryptedSecret encryptedKey = encryptOptionalStorageSecret(
                "cos-secret-key", secretKey);
        PaymentSecretCipher.EncryptedSecret envelope = encryptedId == null ? encryptedKey : encryptedId;
        if (envelope == null) {
            return 0;
        }
        requireSameTargetEnvelope(envelope, encryptedId == null ? envelope : encryptedId,
                encryptedKey == null ? envelope : encryptedKey);

        return jdbcClient.sql("""
                        update storage_runtime_setting
                        set cos_secret_id_ciphertext = :newSecretId,
                            cos_secret_key_ciphertext = :newSecretKey,
                            secret_cipher_version = :newVersion,
                            secret_key_id = :newKeyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :id
                          and secret_revision = :oldRevision
                        """)
                .param("newSecretId", encryptedId == null ? "" : encryptedId.ciphertext())
                .param("newSecretKey", encryptedKey == null ? "" : encryptedKey.ciphertext())
                .param("newVersion", envelope.version())
                .param("newKeyId", envelope.keyId())
                .param("id", candidate.id())
                .param("oldRevision", candidate.revision())
                .update();
    }

    private String decryptOptionalStorageSecret(
            String fieldName,
            String ciphertext,
            StorageEnvelope candidate
    ) {
        if (!StringUtils.hasText(ciphertext)) {
            return "";
        }
        PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(
                StorageRuntimeConfigService.secretContext(fieldName), ciphertext);
        requireMetadata(decrypted, candidate.version(), candidate.keyId());
        return decrypted.plaintext();
    }

    private PaymentSecretCipher.EncryptedSecret encryptOptionalStorageSecret(
            String fieldName,
            String plaintext
    ) {
        if (!StringUtils.hasText(plaintext)) {
            return null;
        }
        return secretCipher.encrypt(StorageRuntimeConfigService.secretContext(fieldName), plaintext);
    }

    private void requireMetadata(
            PaymentSecretCipher.DecryptedSecret decrypted,
            int expectedVersion,
            String expectedKeyId
    ) {
        if (decrypted.version() != expectedVersion
                || !decrypted.keyId().equals(normalizeKeyId(expectedKeyId))) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
    }

    private void requireTargetEnvelope(PaymentSecretCipher.EncryptedSecret encrypted) {
        if (encrypted.version() != 2
                || !encrypted.keyId().equals(normalizeKeyId(properties.activeKeyId()))) {
            throw new IllegalStateException("Secret cipher did not use the configured active key");
        }
    }

    private void requireSameTargetEnvelope(PaymentSecretCipher.EncryptedSecret... secrets) {
        if (secrets.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        requireTargetEnvelope(secrets[0]);
        for (PaymentSecretCipher.EncryptedSecret secret : secrets) {
            requireTargetEnvelope(secret);
            if (secret.version() != secrets[0].version()
                    || !secret.keyId().equals(secrets[0].keyId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                normalizeKeyId(left).getBytes(StandardCharsets.UTF_8),
                normalizeKeyId(right).getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeKeyId(String value) {
        return value == null ? "" : value.trim();
    }

    private record PaymentConfigEnvelope(
            long id,
            String ciphertext,
            int version,
            String keyId,
            long revision
    ) {
    }

    private record SnapshotEnvelope(
            String fingerprint,
            String apiV3KeyCiphertext,
            String privateKeyPemCiphertext,
            String publicKeyPemCiphertext,
            int version,
            String keyId,
            long revision
    ) {
    }

    private record StorageEnvelope(
            long id,
            String secretIdCiphertext,
            String secretKeyCiphertext,
            int version,
            String keyId,
            long revision
    ) {
    }
}
