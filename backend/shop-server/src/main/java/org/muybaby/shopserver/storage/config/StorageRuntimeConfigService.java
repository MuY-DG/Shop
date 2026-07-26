package org.muybaby.shopserver.storage.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.storage.StorageProperties;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class StorageRuntimeConfigService {

    private static final long SETTING_ID = 1L;
    private static final Pattern REGION_PATTERN = Pattern.compile("[a-z0-9-]{2,64}");
    private static final Pattern BUCKET_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,116}-[0-9]{5,20}");

    private final StorageProperties properties;
    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;

    public StorageRuntimeConfigService(
            StorageProperties properties,
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
    }

    public ResolvedStorageConfig effective() {
        return persistedRow().map(this::resolve).orElseGet(this::defaultConfig);
    }

    public AdminStorageConfigResponse current() {
        Optional<StorageSettingRow> persisted = persistedRow();
        ResolvedStorageConfig config = persisted.map(this::resolve).orElseGet(this::defaultConfig);
        return response(config, persisted.isPresent());
    }

    @Transactional
    public AdminStorageConfigResponse update(AdminStorageConfigRequest request) {
        if (request == null) {
            throw validationFailure();
        }
        Optional<StorageSettingRow> existing = persistedRow();
        ResolvedStorageConfig current = existing.map(this::resolve).orElseGet(this::defaultConfig);
        StorageProviderKind provider = parseProvider(request.provider());
        String localRoot = requireTextOrFallback(request.localRoot(), current.localRoot(), 500);
        String cosRegion = optionalTextOrFallback(request.cosRegion(), current.cosRegion(), 64);
        String cosBucket = optionalTextOrFallback(request.cosBucket(), current.cosBucket(), 128);
        String cosSecretId = textOrFallback(request.cosSecretId(), current.cosSecretId(), 256);
        String cosSecretKey = textOrFallback(request.cosSecretKey(), current.cosSecretKey(), 256);

        if (provider == StorageProviderKind.TENCENT_COS) {
            requireCosConfig(cosRegion, cosBucket, cosSecretId, cosSecretKey);
        }

        String localPublicBaseUrl = normalizePublicBaseUrl(requestedProviderUrl(
                request.localPublicBaseUrl(),
                provider == StorageProviderKind.LOCAL ? request.publicBaseUrl() : null,
                current.localPublicBaseUrl()
        ));
        if (!StringUtils.hasText(localPublicBaseUrl)) {
            throw validationFailure();
        }
        String cosPublicBaseUrl = normalizePublicBaseUrl(requestedProviderUrl(
                request.cosPublicBaseUrl(),
                provider == StorageProviderKind.TENCENT_COS ? request.publicBaseUrl() : null,
                current.cosPublicBaseUrl()
        ));
        if (!StringUtils.hasText(cosPublicBaseUrl)
                && StringUtils.hasText(cosBucket)
                && StringUtils.hasText(cosRegion)) {
            cosPublicBaseUrl = defaultCosPublicBaseUrl(cosBucket, cosRegion);
        }
        String publicBaseUrl = provider == StorageProviderKind.TENCENT_COS
                ? cosPublicBaseUrl
                : localPublicBaseUrl;

        PaymentSecretCipher.EncryptedSecret encryptedSecretId = encryptIfPresent(
                "cos-secret-id", cosSecretId);
        PaymentSecretCipher.EncryptedSecret encryptedSecretKey = encryptIfPresent(
                "cos-secret-key", cosSecretKey);
        EnvelopeMetadata envelope = requireSameEnvelope(encryptedSecretId, encryptedSecretKey);
        int updatedRows = jdbcClient.sql("""
                        update storage_runtime_setting
                        set provider = :provider,
                            public_base_url = :publicBaseUrl,
                            local_public_base_url = :localPublicBaseUrl,
                            cos_public_base_url = :cosPublicBaseUrl,
                            local_root = :localRoot,
                            cos_region = :cosRegion,
                            cos_bucket = :cosBucket,
                            cos_secret_id_ciphertext = :cosSecretIdCiphertext,
                            cos_secret_key_ciphertext = :cosSecretKeyCiphertext,
                            secret_cipher_version = :secretCipherVersion,
                            secret_key_id = :secretKeyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = null,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("provider", provider.name())
                .param("publicBaseUrl", publicBaseUrl)
                .param("localPublicBaseUrl", localPublicBaseUrl)
                .param("cosPublicBaseUrl", cosPublicBaseUrl)
                .param("localRoot", localRoot)
                .param("cosRegion", cosRegion)
                .param("cosBucket", cosBucket)
                .param("cosSecretIdCiphertext", ciphertext(encryptedSecretId))
                .param("cosSecretKeyCiphertext", ciphertext(encryptedSecretKey))
                .param("secretCipherVersion", envelope.version())
                .param("secretKeyId", envelope.keyId())
                .param("id", SETTING_ID)
                .update();
        if (updatedRows == 0) {
            jdbcClient.sql("""
                            insert into storage_runtime_setting
                                (id, provider, public_base_url, local_public_base_url,
                                 cos_public_base_url, local_root, cos_region, cos_bucket,
                                 cos_secret_id_ciphertext, cos_secret_key_ciphertext)
                        values
                                (:id, :provider, :publicBaseUrl, :localPublicBaseUrl,
                                 :cosPublicBaseUrl, :localRoot, :cosRegion, :cosBucket,
                                 :cosSecretIdCiphertext, :cosSecretKeyCiphertext)
                            """)
                    .param("id", SETTING_ID)
                    .param("provider", provider.name())
                    .param("publicBaseUrl", publicBaseUrl)
                    .param("localPublicBaseUrl", localPublicBaseUrl)
                    .param("cosPublicBaseUrl", cosPublicBaseUrl)
                    .param("localRoot", localRoot)
                    .param("cosRegion", cosRegion)
                    .param("cosBucket", cosBucket)
                    .param("cosSecretIdCiphertext", ciphertext(encryptedSecretId))
                    .param("cosSecretKeyCiphertext", ciphertext(encryptedSecretKey))
                    .update();
            jdbcClient.sql("""
                            update storage_runtime_setting
                            set secret_cipher_version = :secretCipherVersion,
                                secret_key_id = :secretKeyId,
                                secret_revision = secret_revision + 1
                            where id = :id
                            """)
                    .param("secretCipherVersion", envelope.version())
                    .param("secretKeyId", envelope.keyId())
                    .param("id", SETTING_ID)
                    .update();
        }
        return current();
    }

    public StorageProviderKind parseProvider(String value) {
        if (!StringUtils.hasText(value)) {
            throw validationFailure();
        }
        try {
            return StorageProviderKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw validationFailure();
        }
    }

    private Optional<StorageSettingRow> persistedRow() {
        return jdbcClient.sql("""
                        select provider, public_base_url, local_public_base_url,
                               cos_public_base_url, local_root, cos_region, cos_bucket,
                               cos_secret_id_ciphertext, cos_secret_key_ciphertext,
                               secret_cipher_version, secret_key_id
                        from storage_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::mapRow)
                .optional();
    }

    private ResolvedStorageConfig resolve(StorageSettingRow row) {
        StorageProviderKind provider = parseProvider(row.provider());
        String localPublicBaseUrl = normalizePublicBaseUrl(row.localPublicBaseUrl());
        String cosPublicBaseUrl = normalizePublicBaseUrl(row.cosPublicBaseUrl());
        if (provider == StorageProviderKind.LOCAL && !StringUtils.hasText(localPublicBaseUrl)) {
            localPublicBaseUrl = normalizePublicBaseUrl(row.publicBaseUrl());
        }
        if (provider == StorageProviderKind.TENCENT_COS && !StringUtils.hasText(cosPublicBaseUrl)) {
            cosPublicBaseUrl = normalizePublicBaseUrl(row.publicBaseUrl());
        }
        if (!StringUtils.hasText(localPublicBaseUrl)) {
            localPublicBaseUrl = defaultLocalPublicBaseUrl();
        }
        if (!StringUtils.hasText(cosPublicBaseUrl)) {
            cosPublicBaseUrl = configuredCosPublicBaseUrl(row.cosBucket(), row.cosRegion());
        }
        return new ResolvedStorageConfig(
                provider,
                localPublicBaseUrl,
                cosPublicBaseUrl,
                row.localRoot(),
                row.cosRegion(),
                row.cosBucket(),
                decryptIfPresent("cos-secret-id", row.cosSecretIdCiphertext(), row),
                decryptIfPresent("cos-secret-key", row.cosSecretKeyCiphertext(), row)
        );
    }

    private ResolvedStorageConfig defaultConfig() {
        StorageProperties.TencentCos cos = properties.tencentCos();
        StorageProviderKind provider = properties.provider() == null ? StorageProviderKind.LOCAL : properties.provider();
        String region = cos == null ? "" : trim(cos.region());
        String bucket = cos == null ? "" : trim(cos.bucket());
        String cosPublicBaseUrl = configuredCosPublicBaseUrl(bucket, region);
        return new ResolvedStorageConfig(
                provider,
                defaultLocalPublicBaseUrl(),
                cosPublicBaseUrl,
                properties.local() == null ? "var/uploads" : trim(properties.local().root()),
                region,
                bucket,
                cos == null ? "" : trim(cos.secretId()),
                cos == null ? "" : trim(cos.secretKey())
        );
    }

    private AdminStorageConfigResponse response(ResolvedStorageConfig config, boolean persisted) {
        return new AdminStorageConfigResponse(
                config.provider().name(),
                persisted,
                properties.provider() == null ? StorageProviderKind.LOCAL.name() : properties.provider().name(),
                config.publicBaseUrl(),
                config.localPublicBaseUrl(),
                config.cosPublicBaseUrl(),
                config.localRoot(),
                config.cosRegion(),
                config.cosBucket(),
                mask(config.cosSecretId()),
                StringUtils.hasText(config.cosSecretKey())
        );
    }

    private void requireCosConfig(String region, String bucket, String secretId, String secretKey) {
        if (!REGION_PATTERN.matcher(region).matches()
                || !BUCKET_PATTERN.matcher(bucket).matches()
                || !StringUtils.hasText(secretId)
                || !StringUtils.hasText(secretKey)) {
            throw validationFailure();
        }
    }

    private String defaultCosPublicBaseUrl(String bucket, String region) {
        if (!StringUtils.hasText(bucket) || !StringUtils.hasText(region)) {
            return "";
        }
        return "https://" + bucket + ".cos." + region + ".myqcloud.com";
    }

    private String defaultLocalPublicBaseUrl() {
        return normalizePublicBaseUrl(properties.publicBaseUrl());
    }

    private String configuredCosPublicBaseUrl(String bucket, String region) {
        StorageProperties.TencentCos cos = properties.tencentCos();
        String configured = cos == null ? "" : normalizePublicBaseUrl(cos.publicBaseUrl());
        return StringUtils.hasText(configured)
                ? configured
                : defaultCosPublicBaseUrl(bucket, region);
    }

    private String normalizePublicBaseUrl(String value) {
        String normalized = trim(value);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            URI uri = new URI(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw validationFailure();
            }
            return normalized;
        } catch (URISyntaxException ex) {
            throw validationFailure();
        }
    }

    private String requireTextOrFallback(String value, String fallback, int maxLength) {
        String resolved = textOrFallback(value, fallback, maxLength);
        if (!StringUtils.hasText(resolved)) {
            throw validationFailure();
        }
        return resolved;
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

    private String requestedProviderUrl(String providerUrl, String legacyUrl, String fallback) {
        if (providerUrl != null) {
            return providerUrl;
        }
        if (legacyUrl != null) {
            return legacyUrl;
        }
        return fallback;
    }

    private PaymentSecretCipher.EncryptedSecret encryptIfPresent(String fieldName, String value) {
        return StringUtils.hasText(value)
                ? secretCipher.encrypt(secretContext(fieldName), value)
                : null;
    }

    private String decryptIfPresent(String fieldName, String value, StorageSettingRow row) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(
                secretContext(fieldName), value);
        if (decrypted.version() != row.secretCipherVersion()
                || !decrypted.keyId().equals(row.secretKeyId() == null ? "" : row.secretKeyId())) {
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
        PaymentSecretCipher.EncryptedSecret selected = first == null ? second : first;
        if (selected == null) {
            return new EnvelopeMetadata(1, "");
        }
        if (first != null && second != null
                && (first.version() != second.version() || !first.keyId().equals(second.keyId()))) {
            throw validationFailure();
        }
        return new EnvelopeMetadata(selected.version(), selected.keyId());
    }

    private String ciphertext(PaymentSecretCipher.EncryptedSecret secret) {
        return secret == null ? "" : secret.ciphertext();
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

    private StorageSettingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new StorageSettingRow(
                rs.getString("provider"),
                rs.getString("public_base_url"),
                rs.getString("local_public_base_url"),
                rs.getString("cos_public_base_url"),
                rs.getString("local_root"),
                rs.getString("cos_region"),
                rs.getString("cos_bucket"),
                rs.getString("cos_secret_id_ciphertext"),
                rs.getString("cos_secret_key_ciphertext"),
                rs.getInt("secret_cipher_version"),
                rs.getString("secret_key_id")
        );
    }

    private record StorageSettingRow(
            String provider,
            String publicBaseUrl,
            String localPublicBaseUrl,
            String cosPublicBaseUrl,
            String localRoot,
            String cosRegion,
            String cosBucket,
            String cosSecretIdCiphertext,
            String cosSecretKeyCiphertext,
            int secretCipherVersion,
            String secretKeyId
    ) {
    }

    private record EnvelopeMetadata(int version, String keyId) {
    }
}
