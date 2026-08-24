package org.muybaby.shopserver.wechat.platform;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformConfigResponse;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformConfigUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class WechatPlatformConfigService implements WechatPlatformCredentialResolver {

    private static final int MAX_APP_ID_LENGTH = 64;
    private static final int MAX_APP_SECRET_LENGTH = 256;
    private static final String MASKED_SECRET = "********";

    private final WechatPlatformConfigRepository repository;
    private final PaymentSecretCipher secretCipher;

    public WechatPlatformConfigService(
            WechatPlatformConfigRepository repository,
            PaymentSecretCipher secretCipher
    ) {
        this.repository = repository;
        this.secretCipher = secretCipher;
    }

    @Override
    @Transactional(readOnly = true)
    public WechatPlatformCredentials resolve() {
        return repository.find().map(this::resolvePersisted).orElseThrow(this::unavailable);
    }

    @Transactional(readOnly = true)
    public AdminWechatPlatformConfigResponse current() {
        Optional<WechatPlatformConfigEntity> persisted = repository.find();
        if (persisted.isPresent()) {
            WechatPlatformConfigEntity row = persisted.orElseThrow();
            WechatPlatformCredentials resolved = resolvePersisted(row);
            return response(resolved, row.revision(), row.updatedBy(), row.updatedAt());
        }
        return new AdminWechatPlatformConfigResponse(
                false, "NONE", "", "", false, 0, null, null);
    }

    @Transactional
    public AdminWechatPlatformConfigResponse update(
            AdminWechatPlatformConfigUpdateRequest request,
            Long operatorId
    ) {
        requireOperator(operatorId);
        if (request == null || request.version() == null || request.version() < 0) {
            throw validation();
        }
        String appId = requiredText(request.appId(), MAX_APP_ID_LENGTH);
        Optional<WechatPlatformConfigEntity> persisted = repository.find();
        String appSecret;
        if (persisted.isPresent()) {
            WechatPlatformConfigEntity row = persisted.orElseThrow();
            if (row.revision() != request.version()) {
                throw conflict();
            }
            appSecret = StringUtils.hasText(request.appSecret())
                    ? requiredAppSecret(request.appSecret())
                    : resolvePersisted(row).appSecret();
            PaymentSecretCipher.EncryptedSecret encrypted = encrypt(appSecret);
            if (!repository.update(row.revision(), appId, encrypted, operatorId)) {
                throw conflict();
            }
        } else {
            if (request.version() != 0) {
                throw conflict();
            }
            if (!StringUtils.hasText(request.appSecret())) {
                throw validation();
            }
            appSecret = requiredAppSecret(request.appSecret());
            if (!repository.insert(appId, encrypt(appSecret), operatorId)) {
                throw conflict();
            }
        }
        return current();
    }

    @Transactional
    public int rotateSecretIfNeeded() {
        Optional<WechatPlatformConfigEntity> persisted = repository.find();
        if (persisted.isEmpty()) {
            return 0;
        }
        WechatPlatformConfigEntity row = persisted.orElseThrow();
        if (!secretCipher.shouldReencrypt(
                row.secretCipherVersion(), normalizeKeyId(row.secretKeyId()))) {
            return 0;
        }
        PaymentSecretCipher.DecryptedSecret decrypted = decrypt(row);
        PaymentSecretCipher.EncryptedSecret encrypted = encrypt(decrypted.plaintext());
        return repository.rotate(
                row.revision(), row.secretRevision(), encrypted) ? 1 : 0;
    }

    public static PaymentSecretCipher.SecretContext secretContext() {
        return new PaymentSecretCipher.SecretContext(
                "wechat-platform-config",
                Long.toString(WechatPlatformConfigRepository.SETTING_ID),
                "app-secret"
        );
    }

    private WechatPlatformCredentials resolvePersisted(WechatPlatformConfigEntity row) {
        String appId = persistedText(row.appId(), MAX_APP_ID_LENGTH);
        PaymentSecretCipher.DecryptedSecret decrypted = decrypt(row);
        String appSecret = persistedAppSecret(decrypted.plaintext());
        return new WechatPlatformCredentials(
                appId, appSecret, WechatPlatformCredentials.Source.DATABASE);
    }

    private PaymentSecretCipher.DecryptedSecret decrypt(WechatPlatformConfigEntity row) {
        PaymentSecretCipher.DecryptedSecret decrypted;
        try {
            decrypted = secretCipher.decrypt(secretContext(), row.appSecretCiphertext());
        } catch (RuntimeException ex) {
            throw unavailable();
        }
        if (decrypted.version() != row.secretCipherVersion()
                || !decrypted.keyId().equals(normalizeKeyId(row.secretKeyId()))) {
            throw unavailable();
        }
        return decrypted;
    }

    private PaymentSecretCipher.EncryptedSecret encrypt(String appSecret) {
        return secretCipher.encrypt(secretContext(), appSecret);
    }

    private AdminWechatPlatformConfigResponse response(
            WechatPlatformCredentials credentials,
            long version,
            Long updatedBy,
            java.time.LocalDateTime updatedAt
    ) {
        return new AdminWechatPlatformConfigResponse(
                true,
                credentials.source().name(),
                credentials.appId(),
                MASKED_SECRET,
                true,
                version,
                updatedBy,
                updatedAt
        );
    }

    private String requiredText(String value, int maxLength) {
        String normalized = normalizedTextOrNull(value, maxLength);
        if (normalized == null) {
            throw validation();
        }
        return normalized;
    }

    private String persistedText(String value, int maxLength) {
        String normalized = normalizedTextOrNull(value, maxLength);
        if (normalized == null) {
            throw unavailable();
        }
        return normalized;
    }

    private String requiredAppSecret(String value) {
        String normalized = normalizedAppSecretOrNull(value);
        if (normalized == null) {
            throw validation();
        }
        return normalized;
    }

    private String persistedAppSecret(String value) {
        String normalized = normalizedAppSecretOrNull(value);
        if (normalized == null) {
            throw unavailable();
        }
        return normalized;
    }

    private String normalizedAppSecretOrNull(String value) {
        String normalized = normalizedTextOrNull(value, MAX_APP_SECRET_LENGTH);
        return MASKED_SECRET.equals(normalized) ? null : normalized;
    }

    private String normalizedTextOrNull(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)
                || normalized.getBytes(StandardCharsets.UTF_8).length > maxLength
                || normalized.codePoints().anyMatch(this::unsafeCodePoint)) {
            return null;
        }
        return normalized;
    }

    private boolean unsafeCodePoint(int codePoint) {
        return Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }

    private void requireOperator(Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            throw validation();
        }
    }

    private String normalizeKeyId(String value) {
        return value == null ? "" : value.trim();
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.WECHAT_PLATFORM_CONFIG_UNAVAILABLE);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.WECHAT_PLATFORM_CONFIG_CONFLICT);
    }
}
