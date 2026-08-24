package org.muybaby.shopserver.wechat.servicecard.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfigRepository.AuditState;
import org.muybaby.shopserver.wechat.servicecard.config.WechatServiceCardConfigRepository.StoredConfig;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardConfigResponse;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardConfigUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WechatServiceCardConfigService implements WechatServiceCardConfigResolver {

    public static final String MASKED_SECRET = "********";
    private static final int MAX_TEMPLATE_BYTES = 128;
    private static final int MAX_IMAGE_BYTES = 2048;
    private static final int MAX_HOSTS_BYTES = 2048;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]{3,32}");
    private static final Pattern TEMPLATE_RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern HOST_LABEL = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private final WechatServiceCardConfigRepository repository;
    private final PaymentSecretCipher secretCipher;

    public WechatServiceCardConfigService(
            WechatServiceCardConfigRepository repository,
            PaymentSecretCipher secretCipher
    ) {
        this.repository = repository;
        this.secretCipher = secretCipher;
    }

    @Override
    @Transactional(readOnly = true)
    public WechatServiceCardConfig resolve() {
        return repository.find().map(this::resolvePersisted).orElseThrow(this::unavailable);
    }

    @Transactional(readOnly = true)
    public AdminWechatServiceCardConfigResponse current() {
        Optional<WechatServiceCardConfigEntity> persisted = repository.find();
        if (persisted.isPresent()) {
            WechatServiceCardConfigEntity row = persisted.orElseThrow();
            return response(resolvePersisted(row), row.revision(), row.updatedBy(), row.updatedAt());
        }
        return new AdminWechatServiceCardConfigResponse(
                false, "NONE", "", "", List.of(), false, false,
                "", false, "", false, 0, null, null);
    }

    @Transactional
    public AdminWechatServiceCardConfigResponse update(
            AdminWechatServiceCardConfigUpdateRequest request,
            Long operatorId
    ) {
        requireOperator(operatorId);
        if (request == null || request.version() == null || request.version() < 0
                || request.preferOrderSnapshotImages() == null
                || request.callbackEnabled() == null) {
            throw validation();
        }
        rejectMask(request.callbackToken());
        rejectMask(request.callbackEncodingAesKey());
        StaticFields fields = normalizeStaticFields(
                request.accountTemplateRecordId(), request.fallbackProductImage(),
                request.allowedImageHosts(), request.preferOrderSnapshotImages());

        Optional<WechatServiceCardConfigEntity> persisted = repository.find();
        if (persisted.isEmpty()) {
            if (request.version() != 0) {
                throw conflict();
            }
            String token = optionalToken(request.callbackToken());
            String aesKey = optionalAesKey(request.callbackEncodingAesKey());
            requireCallbackPair(request.callbackEnabled(), token, aesKey);
            StoredConfig stored = stored(fields, request.callbackEnabled(), token, aesKey);
            if (!repository.insert(stored, operatorId)) {
                throw conflict();
            }
            repository.appendAudit(1, "CREATE", AuditState.empty(), audit(stored), operatorId);
        } else {
            WechatServiceCardConfigEntity row = persisted.orElseThrow();
            if (row.revision() != request.version()) {
                throw conflict();
            }
            boolean tokenChanged = StringUtils.hasText(request.callbackToken());
            boolean aesChanged = StringUtils.hasText(request.callbackEncodingAesKey());
            String token = tokenChanged
                    ? requiredToken(request.callbackToken())
                    : row.callbackTokenCiphertext() == null ? "" : decryptToken(row);
            String aesKey = aesChanged
                    ? requiredAesKey(request.callbackEncodingAesKey())
                    : row.callbackAesKeyCiphertext() == null ? "" : decryptAesKey(row);
            requireCallbackPair(request.callbackEnabled(), token, aesKey);
            StoredConfig stored = storedForUpdate(
                    row, fields, request.callbackEnabled(), token, aesKey,
                    tokenChanged, aesChanged);
            if (!repository.update(
                    row.revision(), row.callbackTokenSecretRevision(),
                    row.callbackAesKeySecretRevision(), stored,
                    tokenChanged, aesChanged, operatorId)) {
                throw conflict();
            }
            repository.appendAudit(row.revision() + 1, "UPDATE", audit(row), audit(stored), operatorId);
        }
        return current();
    }

    @Transactional
    public int rotateSecretsIfNeeded() {
        Optional<WechatServiceCardConfigEntity> persisted = repository.find();
        if (persisted.isEmpty()) {
            return 0;
        }
        WechatServiceCardConfigEntity row = persisted.orElseThrow();
        int rotated = 0;
        if (row.callbackTokenCiphertext() != null
                && shouldReencrypt(row.callbackTokenCipherVersion(), row.callbackTokenKeyId())) {
            String token = decryptToken(row);
            if (repository.rotateCallbackToken(
                    row.revision(), row.callbackTokenSecretRevision(), encryptToken(token))) {
                rotated++;
            }
        }
        if (row.callbackAesKeyCiphertext() != null
                && shouldReencrypt(row.callbackAesKeyCipherVersion(), row.callbackAesKeyKeyId())) {
            String aesKey = decryptAesKey(row);
            if (repository.rotateCallbackAesKey(
                    row.revision(), row.callbackAesKeySecretRevision(), encryptAesKey(aesKey))) {
                rotated++;
            }
        }
        return rotated;
    }

    public static PaymentSecretCipher.SecretContext callbackTokenContext() {
        return new PaymentSecretCipher.SecretContext(
                "wechat-service-card-config",
                Long.toString(WechatServiceCardConfigRepository.SETTING_ID),
                "callback-token");
    }

    public static PaymentSecretCipher.SecretContext callbackAesKeyContext() {
        return new PaymentSecretCipher.SecretContext(
                "wechat-service-card-config",
                Long.toString(WechatServiceCardConfigRepository.SETTING_ID),
                "callback-aes-key");
    }

    private WechatServiceCardConfig resolvePersisted(WechatServiceCardConfigEntity row) {
        try {
            StaticFields fields = normalizeStaticFields(
                    row.accountTemplateRecordId(), row.fallbackProductImage(),
                    splitHosts(row.allowedImageHosts()), row.preferOrderSnapshotImages());
            String token = row.callbackTokenCiphertext() == null ? "" : decryptToken(row);
            String aesKey = row.callbackAesKeyCiphertext() == null ? "" : decryptAesKey(row);
            requireCallbackPair(row.callbackEnabled(), token, aesKey);
            return new WechatServiceCardConfig(
                    fields.accountTemplateRecordId(), fields.fallbackProductImage(),
                    Set.copyOf(splitHosts(fields.allowedImageHosts())),
                    fields.preferOrderSnapshotImages(), row.callbackEnabled(), token, aesKey,
                    WechatServiceCardConfig.Source.DATABASE);
        } catch (BusinessException ex) {
            throw unavailable();
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private StaticFields normalizeStaticFields(
            String templateRecordId,
            String fallbackImage,
            List<String> allowedHosts,
            boolean preferSnapshot
    ) {
        String template = requiredSafeText(templateRecordId, MAX_TEMPLATE_BYTES);
        if (!TEMPLATE_RECORD_ID.matcher(template).matches()) {
            throw validation();
        }
        String image = requiredSafeText(fallbackImage, MAX_IMAGE_BYTES);
        LinkedHashSet<String> normalizedHosts = new LinkedHashSet<>();
        if (allowedHosts != null) {
            for (String host : allowedHosts) {
                if (!StringUtils.hasText(host)) {
                    continue;
                }
                String normalized = host.trim().toLowerCase(Locale.ROOT);
                if (!validHost(normalized)) {
                    throw validation();
                }
                normalizedHosts.add(normalized);
            }
        }
        if (normalizedHosts.isEmpty()) {
            throw validation();
        }
        String hosts = joinHosts(normalizedHosts);
        if (hosts.getBytes(StandardCharsets.UTF_8).length > MAX_HOSTS_BYTES
                || !validImage(image, normalizedHosts)) {
            throw validation();
        }
        return new StaticFields(template, image, hosts, preferSnapshot);
    }

    private boolean validImage(String value, Set<String> allowedHosts) {
        if (!WechatServiceCardProperties.validPublicImage(value, allowedHosts)) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute() && StringUtils.hasText(uri.getRawPath());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean validHost(String host) {
        if (host.length() > 253 || host.endsWith(".") || host.contains("..")) {
            return false;
        }
        String[] labels = host.split("\\.");
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private List<String> splitHosts(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    private String joinHosts(Set<String> hosts) {
        return String.join(",", hosts.stream().sorted().toList());
    }

    private String requiredSafeText(String value, int maxBytes) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)
                || normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || normalized.codePoints().anyMatch(this::unsafeCodePoint)) {
            throw validation();
        }
        return normalized;
    }

    private String optionalToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return requiredToken(value);
    }

    private String requiredToken(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!TOKEN.matcher(normalized).matches()) {
            throw validation();
        }
        return normalized;
    }

    private String optionalAesKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return requiredAesKey(value);
    }

    private String requiredAesKey(String value) {
        String normalized = value == null ? "" : value.trim();
        try {
            if (!normalized.matches("[A-Za-z0-9]{43}")
                    || Base64.getDecoder().decode(normalized + "=").length != 32) {
                throw validation();
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw validation();
        }
    }

    private void requireCallbackPair(boolean enabled, String token, String aesKey) {
        boolean tokenConfigured = StringUtils.hasText(token);
        boolean aesConfigured = StringUtils.hasText(aesKey);
        if (tokenConfigured != aesConfigured || (enabled && !tokenConfigured)) {
            throw validation();
        }
        if (tokenConfigured) {
            requiredToken(token);
            requiredAesKey(aesKey);
        }
    }

    private StoredConfig stored(
            StaticFields fields,
            boolean callbackEnabled,
            String token,
            String aesKey
    ) {
        return new StoredConfig(
                fields.accountTemplateRecordId(), fields.fallbackProductImage(),
                fields.allowedImageHosts(), fields.preferOrderSnapshotImages(), callbackEnabled,
                StringUtils.hasText(token) ? encryptToken(token) : null,
                StringUtils.hasText(aesKey) ? encryptAesKey(aesKey) : null);
    }

    private StoredConfig storedForUpdate(
            WechatServiceCardConfigEntity row,
            StaticFields fields,
            boolean callbackEnabled,
            String token,
            String aesKey,
            boolean tokenChanged,
            boolean aesChanged
    ) {
        PaymentSecretCipher.EncryptedSecret tokenEnvelope = tokenChanged
                ? encryptToken(token) : envelope(
                        row.callbackTokenCiphertext(), row.callbackTokenCipherVersion(),
                        row.callbackTokenKeyId());
        PaymentSecretCipher.EncryptedSecret aesEnvelope = aesChanged
                ? encryptAesKey(aesKey) : envelope(
                        row.callbackAesKeyCiphertext(), row.callbackAesKeyCipherVersion(),
                        row.callbackAesKeyKeyId());
        return new StoredConfig(
                fields.accountTemplateRecordId(), fields.fallbackProductImage(),
                fields.allowedImageHosts(), fields.preferOrderSnapshotImages(), callbackEnabled,
                tokenEnvelope, aesEnvelope);
    }

    private PaymentSecretCipher.EncryptedSecret envelope(
            String ciphertext,
            Integer version,
            String keyId
    ) {
        if (ciphertext == null) {
            return null;
        }
        if (version == null) {
            throw unavailable();
        }
        return new PaymentSecretCipher.EncryptedSecret(ciphertext, version, normalizeKeyId(keyId));
    }

    private PaymentSecretCipher.EncryptedSecret encryptToken(String value) {
        return secretCipher.encrypt(callbackTokenContext(), value);
    }

    private PaymentSecretCipher.EncryptedSecret encryptAesKey(String value) {
        return secretCipher.encrypt(callbackAesKeyContext(), value);
    }

    private String decryptToken(WechatServiceCardConfigEntity row) {
        return decrypt(
                callbackTokenContext(), row.callbackTokenCiphertext(),
                row.callbackTokenCipherVersion(), row.callbackTokenKeyId(), true);
    }

    private String decryptAesKey(WechatServiceCardConfigEntity row) {
        return decrypt(
                callbackAesKeyContext(), row.callbackAesKeyCiphertext(),
                row.callbackAesKeyCipherVersion(), row.callbackAesKeyKeyId(), false);
    }

    private String decrypt(
            PaymentSecretCipher.SecretContext context,
            String ciphertext,
            Integer expectedVersion,
            String expectedKeyId,
            boolean token
    ) {
        if (!StringUtils.hasText(ciphertext) || expectedVersion == null) {
            throw unavailable();
        }
        try {
            PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(context, ciphertext);
            if (decrypted.version() != expectedVersion
                    || !decrypted.keyId().equals(normalizeKeyId(expectedKeyId))) {
                throw unavailable();
            }
            return token ? requiredToken(decrypted.plaintext())
                    : requiredAesKey(decrypted.plaintext());
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private boolean shouldReencrypt(Integer version, String keyId) {
        return version != null && secretCipher.shouldReencrypt(version, normalizeKeyId(keyId));
    }

    private AdminWechatServiceCardConfigResponse response(
            WechatServiceCardConfig config,
            long version,
            Long updatedBy,
            java.time.LocalDateTime updatedAt
    ) {
        boolean tokenConfigured = StringUtils.hasText(config.callbackToken());
        boolean aesConfigured = StringUtils.hasText(config.callbackEncodingAesKey());
        return new AdminWechatServiceCardConfigResponse(
                true, config.source().name(), config.accountTemplateRecordId(),
                config.fallbackProductImage(), config.allowedImageHosts().stream().sorted().toList(),
                config.preferOrderSnapshotImages(), config.callbackEnabled(),
                tokenConfigured ? MASKED_SECRET : "", tokenConfigured,
                aesConfigured ? MASKED_SECRET : "", aesConfigured,
                version, updatedBy, updatedAt);
    }

    private AuditState audit(StoredConfig config) {
        return new AuditState(
                config.accountTemplateRecordId(), config.fallbackProductImage(),
                config.allowedImageHosts(), config.preferOrderSnapshotImages(),
                config.callbackEnabled(), config.callbackToken() != null,
                config.callbackAesKey() != null);
    }

    private AuditState audit(WechatServiceCardConfigEntity row) {
        return new AuditState(
                row.accountTemplateRecordId(), row.fallbackProductImage(),
                row.allowedImageHosts(), row.preferOrderSnapshotImages(),
                row.callbackEnabled(), row.callbackTokenCiphertext() != null,
                row.callbackAesKeyCiphertext() != null);
    }

    private void rejectMask(String value) {
        if (value != null && MASKED_SECRET.equals(value.trim())) {
            throw validation();
        }
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
        return new BusinessException(ErrorCode.WECHAT_SERVICE_CARD_CONFIG_UNAVAILABLE);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.WECHAT_SERVICE_CARD_CONFIG_CONFLICT);
    }

    private record StaticFields(
            String accountTemplateRecordId,
            String fallbackProductImage,
            String allowedImageHosts,
            boolean preferOrderSnapshotImages
    ) {
    }
}
