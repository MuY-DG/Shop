package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class PaymentConfigResolver {


    private final PaymentProperties properties;
    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;
    private final PrivateStorageFileService privateStorageFileService;
    private final PaymentConfigSourceSettingService sourceSettingService;

    public PaymentConfigResolver(
            PaymentProperties properties,
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher,
            PrivateStorageFileService privateStorageFileService,
            PaymentConfigSourceSettingService sourceSettingService
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
        this.privateStorageFileService = privateStorageFileService;
        this.sourceSettingService = sourceSettingService;
    }

    public ResolvedPaymentConfig resolve() {
        return resolve(properties, sourceSettingService.currentSource());
    }

    public ResolvedPaymentConfig resolve(PaymentProperties candidate) {
        PaymentConfigSource source = candidate.configSource() == null ? PaymentConfigSource.AUTO : candidate.configSource();
        return resolve(candidate, source);
    }

    public ResolvedPaymentConfig resolve(PaymentConfigSource source) {
        return resolve(properties, source == null ? PaymentConfigSource.AUTO : source);
    }

    private ResolvedPaymentConfig resolve(PaymentProperties candidate, PaymentConfigSource source) {
        if (source == PaymentConfigSource.ENV) {
            return resolveEnv(candidate);
        }
        if (source == PaymentConfigSource.DB) {
            return resolveDb();
        }
        if (isCompleteEnv(candidate)) {
            return resolveEnv(candidate);
        }
        return resolveDb();
    }

    public ResolvedPaymentConfig resolveEnv(PaymentProperties candidate) {
        if (!isCompleteEnv(candidate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        PaymentVerifyMode verifyMode = requireSupportedVerifyMode(candidate.verifyMode());
        return new ResolvedPaymentConfig(
                PaymentConfigSource.ENV,
                null,
                "Environment",
                candidate.enabled(),
                candidate.appId(),
                candidate.mchId(),
                candidate.merchantSerialNo(),
                candidate.apiV3Key(),
                readTextFile(candidate.privateKeyPath()),
                candidate.notifyUrl(),
                candidate.refundNotifyUrl(),
                verifyMode,
                nullToEmpty(candidate.publicKeyId()),
                readOptionalTextFile(candidate.publicKeyPath()),
                null,
                null,
                null
        );
    }

    public ResolvedPaymentConfig resolveDb() {
        PaymentConfigRow row = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled
                        from payment_config
                        where enabled = true
                          and status = 'ACTIVE'
                        order by id desc
                        limit 1
                        """)
                .query(this::mapPaymentConfigRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        PaymentVerifyMode verifyMode = requireSupportedVerifyMode(row.verifyMode());

        return new ResolvedPaymentConfig(
                PaymentConfigSource.DB,
                row.id(),
                row.configName(),
                row.enabled(),
                row.appId(),
                row.mchId(),
                row.merchantSerialNo(),
                secretCipher.decrypt(row.apiV3KeyCiphertext()),
                readPrivateFile(row.privateKeyFileId()),
                row.notifyUrl(),
                row.refundNotifyUrl(),
                verifyMode,
                requiredPublicKeyId(verifyMode, row.wechatPublicKeyId()),
                readPublicKeyFile(verifyMode, row.wechatPublicKeyFileId()),
                row.privateKeyFileId(),
                row.merchantCertificateFileId(),
                row.wechatPublicKeyFileId()
        );
    }

    private boolean isCompleteEnv(PaymentProperties candidate) {
        return candidate.enabled()
                && StringUtils.hasText(candidate.appId())
                && StringUtils.hasText(candidate.mchId())
                && StringUtils.hasText(candidate.merchantSerialNo())
                && StringUtils.hasText(candidate.privateKeyPath())
                && StringUtils.hasText(candidate.apiV3Key())
                && StringUtils.hasText(candidate.notifyUrl())
                && StringUtils.hasText(candidate.refundNotifyUrl())
                && isCompleteVerifyMaterial(candidate);
    }

    private String readPrivateFile(Long fileId) {
        if (fileId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return privateStorageFileService.readSecretText(fileId);
    }

    private String readOptionalPrivateFile(Long fileId) {
        if (fileId == null) {
            return "";
        }
        return privateStorageFileService.readSecretText(fileId);
    }

    private boolean isCompleteVerifyMaterial(PaymentProperties candidate) {
        if (normalizeVerifyMode(candidate.verifyMode()) != PaymentVerifyMode.PUBLIC_KEY) {
            return false;
        }
        return StringUtils.hasText(candidate.publicKeyId())
                && StringUtils.hasText(candidate.publicKeyPath());
    }

    private String requiredPublicKeyId(PaymentVerifyMode verifyMode, String publicKeyId) {
        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY && !StringUtils.hasText(publicKeyId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return nullToEmpty(publicKeyId);
    }

    private String readPublicKeyFile(PaymentVerifyMode verifyMode, Long fileId) {
        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY) {
            return readPrivateFile(fileId);
        }
        return readOptionalPrivateFile(fileId);
    }

    private String readTextFile(String path) {
        if (!StringUtils.hasText(path)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String readOptionalTextFile(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        return readTextFile(path);
    }

    private PaymentVerifyMode normalizeVerifyMode(PaymentVerifyMode verifyMode) {
        return verifyMode == null ? PaymentVerifyMode.PUBLIC_KEY : verifyMode;
    }

    private PaymentVerifyMode requireSupportedVerifyMode(PaymentVerifyMode verifyMode) {
        PaymentVerifyMode normalized = normalizeVerifyMode(verifyMode);
        if (normalized != PaymentVerifyMode.PUBLIC_KEY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private PaymentConfigRow mapPaymentConfigRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentConfigRow(
                rs.getLong("id"),
                rs.getString("config_name"),
                rs.getString("app_id"),
                rs.getString("mch_id"),
                rs.getString("merchant_serial_no"),
                rs.getString("api_v3_key_ciphertext"),
                nullableLong(rs, "private_key_file_id"),
                nullableLong(rs, "merchant_certificate_file_id"),
                normalizeVerifyMode(PaymentVerifyMode.valueOf(rs.getString("verify_mode"))),
                rs.getString("wechat_public_key_id"),
                nullableLong(rs, "wechat_public_key_file_id"),
                rs.getString("notify_url"),
                rs.getString("refund_notify_url"),
                rs.getBoolean("enabled")
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private record PaymentConfigRow(
            Long id,
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3KeyCiphertext,
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            PaymentVerifyMode verifyMode,
            String wechatPublicKeyId,
            Long wechatPublicKeyFileId,
            String notifyUrl,
            String refundNotifyUrl,
            boolean enabled
    ) {
    }
}
