package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigRequest;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.EffectivePaymentConfigResponse;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StoragePurpose;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminPaymentConfigService {

    private static final Set<StoragePurpose> PAYMENT_FILE_PURPOSES = Set.of(StoragePurpose.PAYMENT_CERTIFICATE);
    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 100L;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentSecretCipher paymentSecretCipher;
    private final PrivateStorageFileService privateStorageFileService;
    private final StorageUsageService storageUsageService;

    public AdminPaymentConfigService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PaymentConfigResolver paymentConfigResolver,
            PaymentSecretCipher paymentSecretCipher,
            PrivateStorageFileService privateStorageFileService,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentSecretCipher = paymentSecretCipher;
        this.privateStorageFileService = privateStorageFileService;
        this.storageUsageService = storageUsageService;
    }

    public EffectivePaymentConfigResponse effective() {
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        return new EffectivePaymentConfigResponse(
                null,
                config.source().name(),
                config.source() == PaymentConfigSource.ENV ? "Environment" : "Database",
                mask(config.appId(), 3, 3),
                mask(config.mchId(), 2, 2),
                mask(config.merchantSerialNo(), 3, 3),
                StringUtils.hasText(config.apiV3Key()),
                config.privateKeyFileId(),
                config.merchantCertificateFileId(),
                config.verifyMode().name(),
                mask(config.wechatPublicKeyId(), 4, 4),
                config.wechatPublicKeyFileId(),
                config.notifyUrl(),
                config.refundNotifyUrl(),
                config.enabled(),
                "ACTIVE",
                null,
                null
        );
    }

    public PageResult<AdminPaymentConfigResponse> page(Long current, Long size) {
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        long offset = (pageCurrent - 1) * pageSize;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from payment_config
                        where status = 'ACTIVE'
                        """)
                .query(Long.class)
                .single();

        List<AdminPaymentConfigResponse> records = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled, status, created_at, updated_at
                        from payment_config
                        where status = 'ACTIVE'
                        order by enabled desc, id desc
                        limit :limit offset :offset
                        """)
                .param("limit", pageSize)
                .param("offset", offset)
                .query(this::mapResponse)
                .list();

        return PageResult.of(records, total == null ? 0L : total, pageCurrent, pageSize);
    }

    @Transactional
    public AdminPaymentConfigResponse create(AdminPaymentConfigRequest request) {
        ValidatedConfig validated = validateRequest(request, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into payment_config
                            (config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (:configName, :appId, :mchId, :merchantSerialNo, :apiV3KeyCiphertext,
                             :privateKeyFileId, :merchantCertificateFileId, :verifyMode,
                             :wechatPublicKeyId, :wechatPublicKeyFileId, :notifyUrl, :refundNotifyUrl,
                             false, 'ACTIVE')
                        """,
                new MapSqlParameterSource()
                        .addValue("configName", validated.configName())
                        .addValue("appId", validated.appId())
                        .addValue("mchId", validated.mchId())
                        .addValue("merchantSerialNo", validated.merchantSerialNo())
                        .addValue("apiV3KeyCiphertext", validated.apiV3KeyCiphertext())
                        .addValue("privateKeyFileId", validated.privateKeyFileId())
                        .addValue("merchantCertificateFileId", validated.merchantCertificateFileId())
                        .addValue("verifyMode", validated.verifyMode().name())
                        .addValue("wechatPublicKeyId", validated.wechatPublicKeyId())
                        .addValue("wechatPublicKeyFileId", validated.wechatPublicKeyFileId())
                        .addValue("notifyUrl", validated.notifyUrl())
                        .addValue("refundNotifyUrl", validated.refundNotifyUrl()),
                keyHolder,
                new String[]{"id"});
        Long configId = requireGeneratedId(keyHolder);
        replaceProtectedUsages(configId, validated);
        return requireConfig(configId);
    }

    @Transactional
    public AdminPaymentConfigResponse update(Long configId, AdminPaymentConfigRequest request) {
        PaymentConfigRow existing = requireConfigRow(configId);
        ValidatedConfig validated = validateRequest(request, existing);
        int updatedRows = jdbcClient.sql("""
                        update payment_config
                        set config_name = :configName,
                            app_id = :appId,
                            mch_id = :mchId,
                            merchant_serial_no = :merchantSerialNo,
                            api_v3_key_ciphertext = :apiV3KeyCiphertext,
                            private_key_file_id = :privateKeyFileId,
                            merchant_certificate_file_id = :merchantCertificateFileId,
                            verify_mode = :verifyMode,
                            wechat_public_key_id = :wechatPublicKeyId,
                            wechat_public_key_file_id = :wechatPublicKeyFileId,
                            notify_url = :notifyUrl,
                            refund_notify_url = :refundNotifyUrl,
                            updated_at = current_timestamp
                        where id = :configId
                          and status = 'ACTIVE'
                        """)
                .param("configName", validated.configName())
                .param("appId", validated.appId())
                .param("mchId", validated.mchId())
                .param("merchantSerialNo", validated.merchantSerialNo())
                .param("apiV3KeyCiphertext", validated.apiV3KeyCiphertext())
                .param("privateKeyFileId", validated.privateKeyFileId())
                .param("merchantCertificateFileId", validated.merchantCertificateFileId())
                .param("verifyMode", validated.verifyMode().name())
                .param("wechatPublicKeyId", validated.wechatPublicKeyId())
                .param("wechatPublicKeyFileId", validated.wechatPublicKeyFileId())
                .param("notifyUrl", validated.notifyUrl())
                .param("refundNotifyUrl", validated.refundNotifyUrl())
                .param("configId", configId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        replaceProtectedUsages(configId, validated);
        return requireConfig(configId);
    }

    @Transactional
    public AdminPaymentConfigResponse enable(Long configId) {
        requireConfigRow(configId);
        jdbcClient.sql("""
                        update payment_config
                        set enabled = false,
                            updated_at = current_timestamp
                        where status = 'ACTIVE'
                          and id <> :configId
                        """)
                .param("configId", configId)
                .update();
        int updatedRows = jdbcClient.sql("""
                        update payment_config
                        set enabled = true,
                            updated_at = current_timestamp
                        where id = :configId
                          and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return requireConfig(configId);
    }

    private ValidatedConfig validateRequest(AdminPaymentConfigRequest request, PaymentConfigRow existing) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String configName = requireText(request.configName(), 80);
        String appId = requireText(request.appId(), 64);
        String mchId = requireText(request.mchId(), 32);
        String merchantSerialNo = requireText(request.merchantSerialNo(), 128);
        String notifyUrl = requireText(request.notifyUrl(), 255);
        String refundNotifyUrl = requireText(request.refundNotifyUrl(), 255);
        PaymentVerifyMode verifyMode = parseVerifyMode(request.verifyMode());
        Long privateKeyFileId = requireFileId(request.privateKeyFileId());
        Long merchantCertificateFileId = request.merchantCertificateFileId();
        String wechatPublicKeyId = trimToEmpty(request.wechatPublicKeyId());
        Long wechatPublicKeyFileId = request.wechatPublicKeyFileId();

        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY) {
            wechatPublicKeyId = requireText(wechatPublicKeyId, 128);
            wechatPublicKeyFileId = requireFileId(wechatPublicKeyFileId);
        }
        if (verifyMode == PaymentVerifyMode.CERTIFICATE) {
            merchantCertificateFileId = requireFileId(merchantCertificateFileId);
        }

        validatePaymentFile(privateKeyFileId);
        validatePaymentFile(merchantCertificateFileId);
        validatePaymentFile(wechatPublicKeyFileId);

        String apiV3KeyCiphertext;
        String apiV3Key = trimToNull(request.apiV3Key());
        if (apiV3Key == null) {
            if (existing == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            apiV3KeyCiphertext = existing.apiV3KeyCiphertext();
        } else {
            apiV3KeyCiphertext = paymentSecretCipher.encrypt(apiV3Key);
        }

        return new ValidatedConfig(
                configName,
                appId,
                mchId,
                merchantSerialNo,
                apiV3Key,
                apiV3KeyCiphertext,
                privateKeyFileId,
                merchantCertificateFileId,
                verifyMode,
                wechatPublicKeyId,
                wechatPublicKeyFileId,
                notifyUrl,
                refundNotifyUrl
        );
    }

    private void validatePaymentFile(Long fileId) {
        if (fileId != null) {
            privateStorageFileService.readPrivateText(fileId, PAYMENT_FILE_PURPOSES);
        }
    }

    private void replaceProtectedUsages(Long configId, ValidatedConfig validated) {
        Map<Long, StorageUsageService.UsageAssignment> usagesByFileId = new LinkedHashMap<>();
        addUsage(usagesByFileId, validated.privateKeyFileId(), 1);
        addUsage(usagesByFileId, validated.merchantCertificateFileId(), 2);
        addUsage(usagesByFileId, validated.wechatPublicKeyFileId(), 3);
        storageUsageService.replaceOwnerUsages(
                StorageUsageOwnerType.PAYMENT_CONFIG,
                configId,
                validated.configName(),
                new ArrayList<>(usagesByFileId.values())
        );
    }

    private void addUsage(Map<Long, StorageUsageService.UsageAssignment> usagesByFileId, Long fileId, int sortOrder) {
        if (fileId == null || usagesByFileId.containsKey(fileId)) {
            return;
        }
        usagesByFileId.put(fileId, new StorageUsageService.UsageAssignment(
                fileId,
                StorageFileUsageType.PAYMENT_CONFIG_CERT,
                "",
                sortOrder,
                true
        ));
    }

    private AdminPaymentConfigResponse requireConfig(Long configId) {
        return jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled, status, created_at, updated_at
                        from payment_config
                        where id = :configId
                          and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .query(this::mapResponse)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private PaymentConfigRow requireConfigRow(Long configId) {
        if (configId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return jdbcClient.sql("""
                        select id, api_v3_key_ciphertext
                        from payment_config
                        where id = :configId
                          and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .query((rs, rowNum) -> new PaymentConfigRow(
                        rs.getLong("id"),
                        rs.getString("api_v3_key_ciphertext")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private AdminPaymentConfigResponse mapResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminPaymentConfigResponse(
                rs.getLong("id"),
                PaymentConfigSource.DB.name(),
                rs.getString("config_name"),
                mask(rs.getString("app_id"), 3, 3),
                mask(rs.getString("mch_id"), 2, 2),
                mask(rs.getString("merchant_serial_no"), 3, 3),
                StringUtils.hasText(rs.getString("api_v3_key_ciphertext")),
                nullableLong(rs, "private_key_file_id"),
                nullableLong(rs, "merchant_certificate_file_id"),
                rs.getString("verify_mode"),
                mask(rs.getString("wechat_public_key_id"), 4, 4),
                nullableLong(rs, "wechat_public_key_file_id"),
                rs.getString("notify_url"),
                rs.getString("refund_notify_url"),
                rs.getBoolean("enabled"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private String requireText(String value, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null || trimmed.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return trimmed;
    }

    private Long requireFileId(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return fileId;
    }

    private PaymentVerifyMode parseVerifyMode(String verifyMode) {
        String value = trimToNull(verifyMode);
        if (value == null) {
            return PaymentVerifyMode.PUBLIC_KEY;
        }
        try {
            return PaymentVerifyMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return key.longValue();
    }

    private long normalizeCurrent(Long current) {
        return current == null || current < 1 ? DEFAULT_CURRENT : current;
    }

    private long normalizeSize(Long size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private String mask(String value, int prefixLength, int suffixLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= prefixLength + suffixLength) {
            return "*".repeat(value.length());
        }
        int starCount = value.length() > 10 ? 6 : 3;
        return value.substring(0, prefixLength)
                + "*".repeat(starCount)
                + value.substring(value.length() - suffixLength);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private record PaymentConfigRow(Long id, String apiV3KeyCiphertext) {
    }

    private record ValidatedConfig(
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3Key,
            String apiV3KeyCiphertext,
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            PaymentVerifyMode verifyMode,
            String wechatPublicKeyId,
            Long wechatPublicKeyFileId,
            String notifyUrl,
            String refundNotifyUrl
    ) {
    }
}
