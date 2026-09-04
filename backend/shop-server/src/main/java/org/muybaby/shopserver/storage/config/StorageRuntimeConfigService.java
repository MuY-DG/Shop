package org.muybaby.shopserver.storage.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigResponse;
import org.muybaby.shopserver.storage.dto.AdminStorageBucketListRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageBucketResponse;
import org.muybaby.shopserver.storage.dto.AdminStorageDomainListRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageDomainResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class StorageRuntimeConfigService {

    private static final long SETTING_ID = 1L;
    private static final Pattern REGION_PATTERN = Pattern.compile("[a-z0-9-]{2,64}");
    private static final Pattern BUCKET_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,116}-[0-9]{5,20}");
    private static final Pattern PUBLIC_ORIGIN_HOST_PATTERN = Pattern.compile(
            "(?i)(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?"
    );

    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;
    private final CosCustomDomainVerifier customDomainVerifier;
    private final CosStorageConfigVerifier storageConfigVerifier;
    private final TransactionTemplate transaction;

    public StorageRuntimeConfigService(
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher,
            CosCustomDomainVerifier customDomainVerifier,
            CosStorageConfigVerifier storageConfigVerifier,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
        this.customDomainVerifier = customDomainVerifier;
        this.storageConfigVerifier = storageConfigVerifier;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ResolvedStorageConfig effective() {
        StorageSettingRow row = persistedRow().orElseThrow(this::notConfigured);
        ResolvedStorageConfig config = resolve(row);
        if (!configured(config, row)) {
            throw notConfigured();
        }
        return config;
    }

    public AdminStorageConfigResponse current() {
        Optional<StorageSettingRow> persisted = persistedRow();
        if (persisted.isEmpty()) {
            return response(emptyConfig(), false);
        }
        StorageSettingRow row = persisted.orElseThrow();
        ResolvedStorageConfig config = resolveForEditing(row);
        return response(config, configured(config, row));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AdminStorageBucketResponse> listBuckets(AdminStorageBucketListRequest request) {
        if (request == null) {
            throw validationFailure();
        }
        ResolvedStorageConfig current = persistedRow()
                .map(this::resolveForEditing)
                .orElseGet(this::emptyConfig);
        String secretId = textOrFallback(request.secretId(), current.secretId(), 256);
        String secretKey = textOrFallback(request.secretKey(), current.secretKey(), 256);
        return storageConfigVerifier.listBuckets(secretId, secretKey).stream()
                .map(bucket -> new AdminStorageBucketResponse(
                        bucket.bucket(),
                        bucket.region(),
                        defaultPublicBaseUrl(bucket.bucket(), bucket.region())))
                .toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<AdminStorageDomainResponse> listDomains(AdminStorageDomainListRequest request) {
        if (request == null) {
            throw validationFailure();
        }
        ResolvedStorageConfig current = persistedRow()
                .map(this::resolveForEditing)
                .orElseGet(this::emptyConfig);
        String bucket = textOrFallback(request.bucket(), current.bucket(), 128);
        String region = textOrFallback(request.region(), current.region(), 64);
        String secretId = textOrFallback(request.secretId(), current.secretId(), 256);
        String secretKey = textOrFallback(request.secretKey(), current.secretKey(), 256);
        requireCosConfig(region, bucket, secretId, secretKey);

        String defaultPublicBaseUrl = defaultPublicBaseUrl(bucket, region);
        List<AdminStorageDomainResponse> domains = new ArrayList<>();
        domains.add(new AdminStorageDomainResponse(defaultPublicBaseUrl, "DEFAULT"));
        customDomainVerifier.listEnabledRestDomains(
                        region, bucket, secretId, secretKey).stream()
                .map(domain -> "https://" + domain)
                .filter(domain -> !defaultPublicBaseUrl.equals(domain))
                .map(domain -> new AdminStorageDomainResponse(domain, "CUSTOM"))
                .forEach(domains::add);
        return List.copyOf(domains);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AdminStorageConfigResponse update(AdminStorageConfigRequest request) {
        if (request == null) {
            throw validationFailure();
        }
        ResolvedStorageConfig current = persistedRow()
                .map(this::resolveForEditing)
                .orElseGet(this::emptyConfig);
        String region = optionalTextOrFallback(request.region(), current.region(), 64);
        String bucket = optionalTextOrFallback(request.bucket(), current.bucket(), 128);
        String secretId = textOrFallback(request.secretId(), current.secretId(), 256);
        String secretKey = textOrFallback(request.secretKey(), current.secretKey(), 256);
        requireCosConfig(region, bucket, secretId, secretKey);

        storageConfigVerifier.requireWritable(new ResolvedStorageConfig(
                "", region, bucket, secretId, secretKey));

        String publicBaseUrl = normalizePublicBaseUrl(
                request.publicBaseUrl() == null ? current.publicBaseUrl() : request.publicBaseUrl());
        if (!StringUtils.hasText(publicBaseUrl)) {
            publicBaseUrl = defaultPublicBaseUrl(bucket, region);
        }
        requireAllowedClientOrigin(
                publicBaseUrl, bucket, region, secretId, secretKey);
        String customDomainVerificationFingerprint =
                defaultPublicBaseUrl(bucket, region).equals(publicBaseUrl)
                        ? null
                        : customDomainVerificationFingerprint(
                                publicBaseUrl, region, bucket);

        PaymentSecretCipher.EncryptedSecret encryptedSecretId =
                secretCipher.encrypt(secretContext("cos-secret-id"), secretId);
        PaymentSecretCipher.EncryptedSecret encryptedSecretKey =
                secretCipher.encrypt(secretContext("cos-secret-key"), secretKey);
        EnvelopeMetadata envelope = requireSameEnvelope(encryptedSecretId, encryptedSecretKey);

        String persistedPublicBaseUrl = publicBaseUrl;
        transaction.executeWithoutResult(ignored -> persist(
                persistedPublicBaseUrl,
                region,
                bucket,
                encryptedSecretId,
                encryptedSecretKey,
                envelope,
                customDomainVerificationFingerprint
        ));
        return current();
    }

    private void persist(
            String publicBaseUrl,
            String region,
            String bucket,
            PaymentSecretCipher.EncryptedSecret encryptedSecretId,
            PaymentSecretCipher.EncryptedSecret encryptedSecretKey,
            EnvelopeMetadata envelope,
            String customDomainVerificationFingerprint
    ) {
        int updatedRows = jdbcClient.sql("""
                        update storage_runtime_setting
                        set cos_public_base_url = :publicBaseUrl,
                            cos_region = :region,
                            cos_bucket = :bucket,
                            cos_secret_id_ciphertext = :secretIdCiphertext,
                            cos_secret_key_ciphertext = :secretKeyCiphertext,
                            secret_cipher_version = :secretCipherVersion,
                            secret_key_id = :secretKeyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = null,
                            cos_custom_domain_verification_fingerprint =
                                :customDomainVerificationFingerprint,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("publicBaseUrl", publicBaseUrl)
                .param("region", region)
                .param("bucket", bucket)
                .param("secretIdCiphertext", encryptedSecretId.ciphertext())
                .param("secretKeyCiphertext", encryptedSecretKey.ciphertext())
                .param("secretCipherVersion", envelope.version())
                .param("secretKeyId", envelope.keyId())
                .param("customDomainVerificationFingerprint",
                        customDomainVerificationFingerprint)
                .param("id", SETTING_ID)
                .update();
        if (updatedRows == 0) {
            jdbcClient.sql("""
                            insert into storage_runtime_setting
                                (id, cos_public_base_url, cos_region, cos_bucket,
                                 cos_secret_id_ciphertext, cos_secret_key_ciphertext,
                                 secret_cipher_version, secret_key_id, secret_revision,
                                 cos_custom_domain_verification_fingerprint)
                            values
                                (:id, :publicBaseUrl, :region, :bucket,
                                 :secretIdCiphertext, :secretKeyCiphertext,
                                 :secretCipherVersion, :secretKeyId, 1,
                                 :customDomainVerificationFingerprint)
                            """)
                    .param("id", SETTING_ID)
                    .param("publicBaseUrl", publicBaseUrl)
                    .param("region", region)
                    .param("bucket", bucket)
                    .param("secretIdCiphertext", encryptedSecretId.ciphertext())
                    .param("secretKeyCiphertext", encryptedSecretKey.ciphertext())
                    .param("secretCipherVersion", envelope.version())
                    .param("secretKeyId", envelope.keyId())
                    .param("customDomainVerificationFingerprint",
                            customDomainVerificationFingerprint)
                    .update();
        }
    }

    private Optional<StorageSettingRow> persistedRow() {
        return jdbcClient.sql("""
                        select cos_public_base_url, cos_region, cos_bucket,
                               cos_secret_id_ciphertext, cos_secret_key_ciphertext,
                               secret_cipher_version, secret_key_id,
                               cos_custom_domain_verification_fingerprint
                        from storage_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::mapRow)
                .optional();
    }

    private ResolvedStorageConfig resolve(StorageSettingRow row) {
        String region = optionalText(row.region(), 64);
        String bucket = optionalText(row.bucket(), 128);
        String publicBaseUrl = normalizePublicBaseUrl(row.publicBaseUrl());
        if (!StringUtils.hasText(publicBaseUrl)) {
            publicBaseUrl = defaultPublicBaseUrl(bucket, region);
        }
        if (!allowedClientOrigin(publicBaseUrl, bucket, region)) {
            throw validationFailure();
        }
        return new ResolvedStorageConfig(
                publicBaseUrl,
                region,
                bucket,
                decryptIfPresent("cos-secret-id", row.secretIdCiphertext(), row),
                decryptIfPresent("cos-secret-key", row.secretKeyCiphertext(), row)
        );
    }

    private ResolvedStorageConfig resolveForEditing(StorageSettingRow row) {
        String region = optionalText(row.region(), 64);
        String bucket = optionalText(row.bucket(), 128);
        String publicBaseUrl;
        try {
            publicBaseUrl = normalizePublicBaseUrl(row.publicBaseUrl());
        } catch (BusinessException ex) {
            publicBaseUrl = trim(row.publicBaseUrl());
        }
        if (!StringUtils.hasText(publicBaseUrl)) {
            publicBaseUrl = defaultPublicBaseUrl(bucket, region);
        }
        return new ResolvedStorageConfig(
                publicBaseUrl,
                region,
                bucket,
                decryptIfPresent("cos-secret-id", row.secretIdCiphertext(), row),
                decryptIfPresent("cos-secret-key", row.secretKeyCiphertext(), row)
        );
    }

    private ResolvedStorageConfig emptyConfig() {
        return new ResolvedStorageConfig("", "", "", "", "");
    }

    private AdminStorageConfigResponse response(
            ResolvedStorageConfig config,
            boolean configured
    ) {
        return new AdminStorageConfigResponse(
                configured,
                config.publicBaseUrl(),
                config.region(),
                config.bucket(),
                mask(config.secretId()),
                StringUtils.hasText(config.secretKey())
        );
    }

    private boolean configured(
            ResolvedStorageConfig config,
            StorageSettingRow row
    ) {
        return REGION_PATTERN.matcher(config.region()).matches()
                && BUCKET_PATTERN.matcher(config.bucket()).matches()
                && StringUtils.hasText(config.publicBaseUrl())
                && StringUtils.hasText(config.secretId())
                && StringUtils.hasText(config.secretKey())
                && allowedClientOrigin(
                        config.publicBaseUrl(), config.bucket(), config.region())
                && verifiedClientOrigin(config, row);
    }

    private void requireCosConfig(String region, String bucket, String secretId, String secretKey) {
        if (!REGION_PATTERN.matcher(region).matches()
                || !BUCKET_PATTERN.matcher(bucket).matches()
                || !StringUtils.hasText(secretId)
                || !StringUtils.hasText(secretKey)) {
            throw validationFailure();
        }
    }

    private String defaultPublicBaseUrl(String bucket, String region) {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(region)) {
            return "";
        }
        return "https://" + bucket + ".cos." + region + ".myqcloud.com";
    }

    private void requireAllowedClientOrigin(
            String origin,
            String bucket,
            String region,
            String secretId,
            String secretKey
    ) {
        if (defaultPublicBaseUrl(bucket, region).equals(origin)) {
            return;
        }
        customDomainVerifier.requireEnabledRestDomain(
                URI.create(origin).getHost(), region, bucket, secretId, secretKey);
    }

    private boolean allowedClientOrigin(String origin, String bucket, String region) {
        if (defaultPublicBaseUrl(bucket, region).equals(origin)) {
            return true;
        }
        try {
            return normalizePublicBaseUrl(origin).equals(origin);
        } catch (BusinessException ex) {
            return false;
        }
    }

    private boolean verifiedClientOrigin(
            ResolvedStorageConfig config,
            StorageSettingRow row
    ) {
        if (defaultPublicBaseUrl(config.bucket(), config.region())
                .equals(config.publicBaseUrl())) {
            return true;
        }
        return customDomainVerificationFingerprint(
                config.publicBaseUrl(), config.region(), config.bucket())
                .equals(row.customDomainVerificationFingerprint());
    }

    private String customDomainVerificationFingerprint(
            String publicBaseUrl,
            String region,
            String bucket
    ) {
        String canonical = publicBaseUrl + '\0' + region + '\0' + bucket;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String normalizePublicBaseUrl(String value) {
        String normalized = trim(value);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        try {
            URI uri = new URI(normalized);
            String rawPath = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !StringUtils.hasText(uri.getHost())
                    || !PUBLIC_ORIGIN_HOST_PATTERN.matcher(uri.getHost()).matches()
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || (StringUtils.hasText(rawPath) && !"/".equals(rawPath))
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw validationFailure();
            }
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT);
        } catch (URISyntaxException ex) {
            throw validationFailure();
        }
    }

    private String textOrFallback(String value, String fallback, int maxLength) {
        String normalized = trim(value);
        if (!StringUtils.hasText(normalized)) {
            normalized = trim(fallback);
        }
        if (normalized.length() > maxLength) {
            throw validationFailure();
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength) {
        String normalized = trim(value);
        if (normalized.length() > maxLength) {
            throw validationFailure();
        }
        return normalized;
    }

    private String optionalTextOrFallback(String value, String fallback, int maxLength) {
        return value == null ? optionalText(fallback, maxLength) : optionalText(value, maxLength);
    }

    private String decryptIfPresent(String fieldName, String value, StorageSettingRow row) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        PaymentSecretCipher.DecryptedSecret decrypted =
                secretCipher.decrypt(secretContext(fieldName), value);
        if (decrypted.version() != row.secretCipherVersion()
                || !decrypted.keyId().equals(
                        row.secretKeyId() == null ? "" : row.secretKeyId())) {
            throw validationFailure();
        }
        return decrypted.plaintext();
    }

    public static PaymentSecretCipher.SecretContext secretContext(String fieldName) {
        return new PaymentSecretCipher.SecretContext(
                "storage-runtime-setting", Long.toString(SETTING_ID), fieldName);
    }

    private EnvelopeMetadata requireSameEnvelope(
            PaymentSecretCipher.EncryptedSecret first,
            PaymentSecretCipher.EncryptedSecret second
    ) {
        if (first.version() != second.version() || !first.keyId().equals(second.keyId())) {
            throw validationFailure();
        }
        return new EnvelopeMetadata(first.version(), first.keyId());
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 4) + "******" + value.substring(value.length() - 4);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private BusinessException notConfigured() {
        return new BusinessException(ErrorCode.STORAGE_NOT_CONFIGURED);
    }

    private StorageSettingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StorageSettingRow(
                rs.getString("cos_public_base_url"),
                rs.getString("cos_region"),
                rs.getString("cos_bucket"),
                rs.getString("cos_secret_id_ciphertext"),
                rs.getString("cos_secret_key_ciphertext"),
                rs.getInt("secret_cipher_version"),
                rs.getString("secret_key_id"),
                rs.getString("cos_custom_domain_verification_fingerprint")
        );
    }

    private record StorageSettingRow(
            String publicBaseUrl,
            String region,
            String bucket,
            String secretIdCiphertext,
            String secretKeyCiphertext,
            int secretCipherVersion,
            String secretKeyId,
            String customDomainVerificationFingerprint
    ) {
    }

    private record EnvelopeMetadata(int version, String keyId) {
    }
}
