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
        String cosRegion = optionalText(request.cosRegion(), 64);
        String cosBucket = optionalText(request.cosBucket(), 128);
        String cosSecretId = textOrFallback(request.cosSecretId(), current.cosSecretId(), 256);
        String cosSecretKey = textOrFallback(request.cosSecretKey(), current.cosSecretKey(), 256);

        if (provider == StorageProviderKind.TENCENT_COS) {
            requireCosConfig(cosRegion, cosBucket, cosSecretId, cosSecretKey);
        }

        String publicBaseUrl = normalizePublicBaseUrl(request.publicBaseUrl());
        if (!StringUtils.hasText(publicBaseUrl)) {
            if (provider == StorageProviderKind.TENCENT_COS) {
                publicBaseUrl = defaultCosPublicBaseUrl(cosBucket, cosRegion);
            } else {
                throw validationFailure();
            }
        }

        String secretIdCiphertext = encryptIfPresent(cosSecretId);
        String secretKeyCiphertext = encryptIfPresent(cosSecretKey);
        int updatedRows = jdbcClient.sql("""
                        update storage_runtime_setting
                        set provider = :provider,
                            public_base_url = :publicBaseUrl,
                            local_root = :localRoot,
                            cos_region = :cosRegion,
                            cos_bucket = :cosBucket,
                            cos_secret_id_ciphertext = :cosSecretIdCiphertext,
                            cos_secret_key_ciphertext = :cosSecretKeyCiphertext,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("provider", provider.name())
                .param("publicBaseUrl", publicBaseUrl)
                .param("localRoot", localRoot)
                .param("cosRegion", cosRegion)
                .param("cosBucket", cosBucket)
                .param("cosSecretIdCiphertext", secretIdCiphertext)
                .param("cosSecretKeyCiphertext", secretKeyCiphertext)
                .param("id", SETTING_ID)
                .update();
        if (updatedRows == 0) {
            jdbcClient.sql("""
                            insert into storage_runtime_setting
                                (id, provider, public_base_url, local_root, cos_region, cos_bucket,
                                 cos_secret_id_ciphertext, cos_secret_key_ciphertext)
                            values
                                (:id, :provider, :publicBaseUrl, :localRoot, :cosRegion, :cosBucket,
                                 :cosSecretIdCiphertext, :cosSecretKeyCiphertext)
                            """)
                    .param("id", SETTING_ID)
                    .param("provider", provider.name())
                    .param("publicBaseUrl", publicBaseUrl)
                    .param("localRoot", localRoot)
                    .param("cosRegion", cosRegion)
                    .param("cosBucket", cosBucket)
                    .param("cosSecretIdCiphertext", secretIdCiphertext)
                    .param("cosSecretKeyCiphertext", secretKeyCiphertext)
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
                        select provider, public_base_url, local_root, cos_region, cos_bucket,
                               cos_secret_id_ciphertext, cos_secret_key_ciphertext
                        from storage_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::mapRow)
                .optional();
    }

    private ResolvedStorageConfig resolve(StorageSettingRow row) {
        return new ResolvedStorageConfig(
                parseProvider(row.provider()),
                row.publicBaseUrl(),
                row.localRoot(),
                row.cosRegion(),
                row.cosBucket(),
                decryptIfPresent(row.cosSecretIdCiphertext()),
                decryptIfPresent(row.cosSecretKeyCiphertext())
        );
    }

    private ResolvedStorageConfig defaultConfig() {
        StorageProperties.TencentCos cos = properties.tencentCos();
        StorageProviderKind provider = properties.provider() == null ? StorageProviderKind.LOCAL : properties.provider();
        String region = cos == null ? "" : trim(cos.region());
        String bucket = cos == null ? "" : trim(cos.bucket());
        String cosPublicBaseUrl = cos == null ? "" : normalizePublicBaseUrl(cos.publicBaseUrl());
        String publicBaseUrl = provider == StorageProviderKind.TENCENT_COS
                ? (StringUtils.hasText(cosPublicBaseUrl) ? cosPublicBaseUrl : defaultCosPublicBaseUrl(bucket, region))
                : normalizePublicBaseUrl(properties.publicBaseUrl());
        return new ResolvedStorageConfig(
                provider,
                publicBaseUrl,
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

    private String encryptIfPresent(String value) {
        return StringUtils.hasText(value) ? secretCipher.encrypt(value) : "";
    }

    private String decryptIfPresent(String value) {
        return StringUtils.hasText(value) ? secretCipher.decrypt(value) : "";
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
                rs.getString("local_root"),
                rs.getString("cos_region"),
                rs.getString("cos_bucket"),
                rs.getString("cos_secret_id_ciphertext"),
                rs.getString("cos_secret_key_ciphertext")
        );
    }

    private record StorageSettingRow(
            String provider,
            String publicBaseUrl,
            String localRoot,
            String cosRegion,
            String cosBucket,
            String cosSecretIdCiphertext,
            String cosSecretKeyCiphertext
    ) {
    }
}
