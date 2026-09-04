package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigMutationLock;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.PaymentPemValidator;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigRequest;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.EffectivePaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.EffectivePaymentConfigStateResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class AdminPaymentConfigService {

    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_SIZE = 20L;
    private static final long MAX_SIZE = 100L;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentConfigMutationLock paymentConfigMutationLock;
    private final PaymentNotificationRouteService paymentNotificationRouteService;
    private final PaymentSecretCipher paymentSecretCipher;
    private final PaymentPemValidator paymentPemValidator;
    private final PaymentConfigVerifier paymentConfigVerifier;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public AdminPaymentConfigService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PaymentConfigResolver paymentConfigResolver,
            PaymentConfigMutationLock paymentConfigMutationLock,
            PaymentNotificationRouteService paymentNotificationRouteService,
            PaymentSecretCipher paymentSecretCipher,
            PaymentPemValidator paymentPemValidator,
            PaymentConfigVerifier paymentConfigVerifier,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentConfigMutationLock = paymentConfigMutationLock;
        this.paymentNotificationRouteService = paymentNotificationRouteService;
        this.paymentSecretCipher = paymentSecretCipher;
        this.paymentPemValidator = paymentPemValidator;
        this.paymentConfigVerifier = paymentConfigVerifier;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public EffectivePaymentConfigStateResponse effective() {
        return paymentConfigResolver.resolveAvailable()
                .map(config -> new EffectivePaymentConfigStateResponse(true, toEffectiveResponse(config)))
                .orElseGet(() -> new EffectivePaymentConfigStateResponse(false, null));
    }

    private EffectivePaymentConfigResponse toEffectiveResponse(ResolvedPaymentConfig config) {
        return new EffectivePaymentConfigResponse(
                config.configId(),
                config.configName(),
                mask(config.appId(), 3, 3),
                mask(config.mchId(), 2, 2),
                mask(config.merchantSerialNo(), 3, 3),
                StringUtils.hasText(config.apiV3Key()),
                StringUtils.hasText(config.privateKeyPem()),
                config.verifyMode().name(),
                mask(config.wechatPublicKeyId(), 4, 4),
                StringUtils.hasText(config.wechatPublicKeyPem()),
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
                        select count(*) from payment_config where status = 'ACTIVE'
                        """)
                .query(Long.class)
                .single();

        List<AdminPaymentConfigResponse> records = jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                               verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
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

    public AdminPaymentConfigResponse create(AdminPaymentConfigRequest request) {
        return outsideTransaction(() -> {
            ValidatedConfig validated = validateRequest(request, null);
            return requireTransactionResult(requiresNewTransaction.execute(status -> createInTransaction(validated)));
        });
    }

    private AdminPaymentConfigResponse createInTransaction(ValidatedConfig validated) {
        PaymentSecretCipher.EncryptionMetadata encryptionMetadata =
                paymentSecretCipher.activeEncryptionMetadata();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into payment_config
                            (config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                             enabled, status, secret_cipher_version, secret_key_id)
                        values
                            (:configName, :appId, :mchId, :merchantSerialNo, '', '', '',
                             :verifyMode, :wechatPublicKeyId, :notifyUrl,
                             :refundNotifyUrl, false, 'ACTIVE', :cipherVersion, :keyId)
                        """,
                new MapSqlParameterSource()
                        .addValue("configName", validated.configName())
                        .addValue("appId", validated.appId())
                        .addValue("mchId", validated.mchId())
                        .addValue("merchantSerialNo", validated.merchantSerialNo())
                        .addValue("verifyMode", validated.verifyMode().name())
                        .addValue("wechatPublicKeyId", validated.wechatPublicKeyId())
                        .addValue("notifyUrl", validated.notifyUrl())
                        .addValue("refundNotifyUrl", validated.refundNotifyUrl())
                        .addValue("cipherVersion", encryptionMetadata.version())
                        .addValue("keyId", encryptionMetadata.keyId()),
                keyHolder,
                new String[]{"id"});
        Long configId = requireGeneratedId(keyHolder);
        EncryptedMaterial encrypted = encryptMaterial(
                configId,
                validated.apiV3Key(),
                validated.privateKeyPem(),
                validated.wechatPublicKeyPem()
        );
        int encryptedRows = jdbcClient.sql("""
                        update payment_config
                        set api_v3_key_ciphertext = :apiV3KeyCiphertext,
                            private_key_pem_ciphertext = :privateKeyPemCiphertext,
                            wechat_public_key_pem_ciphertext = :publicKeyPemCiphertext,
                            secret_cipher_version = :cipherVersion,
                            secret_key_id = :keyId,
                            secret_revision = secret_revision + 1
                        where id = :configId
                          and api_v3_key_ciphertext = ''
                          and private_key_pem_ciphertext = ''
                          and wechat_public_key_pem_ciphertext = ''
                        """)
                .param("apiV3KeyCiphertext", encrypted.apiV3Key().ciphertext())
                .param("privateKeyPemCiphertext", encrypted.privateKeyPem().ciphertext())
                .param("publicKeyPemCiphertext", encrypted.wechatPublicKeyPem().ciphertext())
                .param("cipherVersion", encrypted.apiV3Key().version())
                .param("keyId", encrypted.apiV3Key().keyId())
                .param("configId", configId)
                .update();
        if (encryptedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return requireConfig(configId);
    }

    public AdminPaymentConfigResponse update(Long configId, AdminPaymentConfigRequest request) {
        return outsideTransaction(() -> {
            PaymentConfigRow observed = requireConfigRow(configId, false);
            ValidatedConfig validated = validateRequest(request, observed);
            ResolvedPaymentConfig resolved = paymentConfigResolver.resolveForPaymentConfigId(configId);
            requireResolvedConfig(observed, resolved);

            String apiV3Key = validated.apiV3Key() == null
                    ? validateApiV3Key(resolved.apiV3Key())
                    : validated.apiV3Key();
            String privateKeyPem = validated.privateKeyPem() == null
                    ? paymentPemValidator.validatePrivateKey(resolved.privateKeyPem())
                    : validated.privateKeyPem();
            String publicKeyPem = validated.wechatPublicKeyPem() == null
                    ? paymentPemValidator.validatePublicKey(resolved.wechatPublicKeyPem())
                    : validated.wechatPublicKeyPem();
            return requireTransactionResult(requiresNewTransaction.execute(status -> updateInTransaction(
                    configId,
                    observed,
                    validated,
                    apiV3Key,
                    privateKeyPem,
                    publicKeyPem
            )));
        });
    }

    private AdminPaymentConfigResponse updateInTransaction(
            Long configId,
            PaymentConfigRow observed,
            ValidatedConfig validated,
            String apiV3Key,
            String privateKeyPem,
            String publicKeyPem
    ) {
        PaymentConfigRow locked = requireConfigRow(configId, true);
        requireUnchangedConfig(observed, locked);
        rejectReferencedConfigMutation(configId);
        EncryptedMaterial encrypted = encryptMaterial(configId, apiV3Key, privateKeyPem, publicKeyPem);
        int updatedRows = jdbcClient.sql("""
                        update payment_config
                        set config_name = :configName,
                            app_id = :appId,
                            mch_id = :mchId,
                            merchant_serial_no = :merchantSerialNo,
                            api_v3_key_ciphertext = :apiV3KeyCiphertext,
                            private_key_pem_ciphertext = :privateKeyPemCiphertext,
                            wechat_public_key_pem_ciphertext = :publicKeyPemCiphertext,
                            secret_cipher_version = :secretCipherVersion,
                            secret_key_id = :secretKeyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = null,
                            verify_mode = :verifyMode,
                            wechat_public_key_id = :wechatPublicKeyId,
                            notify_url = :notifyUrl,
                            refund_notify_url = :refundNotifyUrl,
                            updated_at = current_timestamp
                        where id = :configId and status = 'ACTIVE'
                        """)
                .param("configName", validated.configName())
                .param("appId", validated.appId())
                .param("mchId", validated.mchId())
                .param("merchantSerialNo", validated.merchantSerialNo())
                .param("apiV3KeyCiphertext", encrypted.apiV3Key().ciphertext())
                .param("privateKeyPemCiphertext", encrypted.privateKeyPem().ciphertext())
                .param("publicKeyPemCiphertext", encrypted.wechatPublicKeyPem().ciphertext())
                .param("secretCipherVersion", encrypted.apiV3Key().version())
                .param("secretKeyId", encrypted.apiV3Key().keyId())
                .param("verifyMode", validated.verifyMode().name())
                .param("wechatPublicKeyId", validated.wechatPublicKeyId())
                .param("notifyUrl", validated.notifyUrl())
                .param("refundNotifyUrl", validated.refundNotifyUrl())
                .param("configId", configId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return requireConfig(configId);
    }

    private void rejectReferencedConfigMutation(Long configId) {
        boolean referenced = jdbcClient.sql("""
                        select count(*)
                        from (
                            select id from payment_order where payment_config_id = :configId
                            union all
                            select id from purged_payment_identity where payment_config_id = :configId
                            union all
                            select id from purged_refund_identity where payment_config_id = :configId
                        ) referenced_payment_config
                        """)
                .param("configId", configId)
                .query(Long.class)
                .single() > 0;
        if (referenced) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    public void delete(Long configId, Long operatorId) {
        if (operatorId == null || operatorId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        requireTransactionResult(requiresNewTransaction.execute(status -> {
            paymentConfigMutationLock.acquire();
            PaymentConfigRow locked = requireConfigRow(
                    configId,
                    true,
                    ErrorCode.PAYMENT_CONFIG_UNAVAILABLE
            );
            if (locked.enabled()) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIG_ENABLED_DELETE_FORBIDDEN);
            }
            int updatedRows = jdbcClient.sql("""
                            update payment_config
                            set status = 'DELETED',
                                enabled = false,
                                deleted_at = current_timestamp,
                                deleted_by = :operatorId,
                                updated_at = current_timestamp
                            where id = :configId
                              and status = 'ACTIVE'
                              and enabled = false
                            """)
                    .param("operatorId", operatorId)
                    .param("configId", configId)
                    .update();
            if (updatedRows != 1) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIG_CONFLICT);
            }
            return Boolean.TRUE;
        }));
    }

    public AdminPaymentConfigResponse enable(Long configId) {
        return outsideTransaction(() -> {
            StoredConfigSnapshot storedConfig = inspectStoredConfig(configId, false, null);
            paymentConfigVerifier.requireUsable(storedConfig.resolved());
            return requireTransactionResult(requiresNewTransaction.execute(status ->
                    enableInTransaction(configId, storedConfig)));
        });
    }

    private AdminPaymentConfigResponse enableInTransaction(Long configId, StoredConfigSnapshot storedConfig) {
        revalidateStoredConfig(storedConfig, true);
        jdbcClient.sql("""
                        update payment_config set enabled = false, updated_at = current_timestamp
                        where status = 'ACTIVE' and id <> :configId
                        """)
                .param("configId", configId)
                .update();
        int updatedRows = jdbcClient.sql("""
                        update payment_config set enabled = true, updated_at = current_timestamp
                        where id = :configId and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return requireConfig(configId);
    }

    private StoredConfigSnapshot inspectStoredConfig(
            Long configId,
            boolean requireEnabled,
            ResolvedPaymentConfig previouslyResolved
    ) {
        PaymentConfigRow observed = requireConfigRow(configId, false);
        if (requireEnabled && !observed.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ResolvedPaymentConfig resolved = previouslyResolved == null
                ? paymentConfigResolver.resolveForPaymentConfigId(configId)
                : previouslyResolved;
        requireResolvedConfig(observed, resolved);
        requireNotificationRouteReady(resolved);
        PaymentConfigRow confirmed = requireConfigRow(configId, false);
        requireUnchangedConfig(observed, confirmed);
        return new StoredConfigSnapshot(confirmed, resolved);
    }

    private void revalidateStoredConfig(StoredConfigSnapshot storedConfig, boolean lockAllConfigs) {
        if (lockAllConfigs) {
            lockActiveConfigsInOrder();
        }
        PaymentConfigRow locked = requireConfigRow(storedConfig.config().id(), true);
        requireUnchangedConfig(storedConfig.config(), locked);
    }

    private void lockActiveConfigsInOrder() {
        jdbcClient.sql("""
                        select id from payment_config where status = 'ACTIVE' order by id for update
                        """)
                .query(Long.class)
                .list();
    }

    private void requireResolvedConfig(PaymentConfigRow row, ResolvedPaymentConfig resolved) {
        boolean matches = resolved != null
                && resolved.source() == PaymentConfigSource.DB
                && Objects.equals(row.id(), resolved.configId())
                && Objects.equals(row.configName(), resolved.configName())
                && row.enabled() == resolved.enabled()
                && Objects.equals(row.appId(), resolved.appId())
                && Objects.equals(row.mchId(), resolved.mchId())
                && Objects.equals(row.merchantSerialNo(), resolved.merchantSerialNo())
                && secretEquals(decryptMaterial(
                        PaymentConfigResolver.apiV3KeyContext(row.id()), row.apiV3KeyCiphertext(), row),
                        resolved.apiV3Key())
                && secretEquals(readPrivateKey(row), resolved.privateKeyPem())
                && row.verifyMode() == resolved.verifyMode()
                && Objects.equals(row.wechatPublicKeyId(), resolved.wechatPublicKeyId())
                && secretEquals(readPublicKey(row), resolved.wechatPublicKeyPem())
                && Objects.equals(row.notifyUrl(), resolved.notifyUrl())
                && Objects.equals(row.refundNotifyUrl(), resolved.refundNotifyUrl());
        if (!matches) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private String readPrivateKey(PaymentConfigRow row) {
        return decryptMaterial(
                PaymentConfigResolver.privateKeyPemContext(row.id()), row.privateKeyPemCiphertext(), row);
    }

    private String readPublicKey(PaymentConfigRow row) {
        return decryptMaterial(
                PaymentConfigResolver.wechatPublicKeyPemContext(row.id()),
                row.wechatPublicKeyPemCiphertext(),
                row
        );
    }

    private String decryptMaterial(
            PaymentSecretCipher.SecretContext context,
            String ciphertext,
            PaymentConfigRow row
    ) {
        try {
            PaymentSecretCipher.DecryptedSecret decrypted = paymentSecretCipher.decrypt(context, ciphertext);
            if (decrypted.version() != row.secretCipherVersion()
                    || !decrypted.keyId().equals(nullToEmpty(row.secretKeyId()))) {
                throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
            }
            return decrypted.plaintext();
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
    }

    private EncryptedMaterial encryptMaterial(
            Long configId,
            String apiV3Key,
            String privateKeyPem,
            String wechatPublicKeyPem
    ) {
        PaymentSecretCipher.EncryptedSecret encryptedApiV3Key = paymentSecretCipher.encrypt(
                PaymentConfigResolver.apiV3KeyContext(configId), apiV3Key);
        PaymentSecretCipher.EncryptedSecret encryptedPrivateKey = paymentSecretCipher.encrypt(
                PaymentConfigResolver.privateKeyPemContext(configId), privateKeyPem);
        PaymentSecretCipher.EncryptedSecret encryptedPublicKey = paymentSecretCipher.encrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(configId), wechatPublicKeyPem);
        requireSameEnvelope(encryptedApiV3Key, encryptedPrivateKey, encryptedPublicKey);
        return new EncryptedMaterial(encryptedApiV3Key, encryptedPrivateKey, encryptedPublicKey);
    }

    private void requireSameEnvelope(PaymentSecretCipher.EncryptedSecret... secrets) {
        if (secrets.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        for (PaymentSecretCipher.EncryptedSecret secret : secrets) {
            if (secret == null
                    || secret.version() != secrets[0].version()
                    || !Objects.equals(secret.keyId(), secrets[0].keyId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
    }

    private boolean secretEquals(String left, String right) {
        return MessageDigest.isEqual(
                nullToEmpty(left).getBytes(StandardCharsets.UTF_8),
                nullToEmpty(right).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void requireNotificationRouteReady(ResolvedPaymentConfig resolved) {
        if (resolved == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        paymentNotificationRouteService.validateRoutedBaseUrl(resolved.notifyUrl());
        paymentNotificationRouteService.validateRoutedBaseUrl(resolved.refundNotifyUrl());
    }

    private void requireUnchangedConfig(PaymentConfigRow observed, PaymentConfigRow current) {
        if (!Objects.equals(observed, current)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private <T> T outsideTransaction(Supplier<T> action) {
        return requireTransactionResult(withoutTransaction.execute(status -> action.get()));
    }

    private <T> T requireTransactionResult(T result) {
        return Objects.requireNonNull(result);
    }

    private ValidatedConfig validateRequest(AdminPaymentConfigRequest request, PaymentConfigRow existing) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String configName = requireText(request.configName(), 80);
        String appId = requireTextOrExisting(request.appId(), 64, existing == null ? null : existing.appId());
        String mchId = requireTextOrExisting(request.mchId(), 32, existing == null ? null : existing.mchId());
        String merchantSerialNo = requireTextOrExisting(
                request.merchantSerialNo(), 128, existing == null ? null : existing.merchantSerialNo());
        String notifyUrl = requireText(request.notifyUrl(), 255);
        String refundNotifyUrl = requireText(request.refundNotifyUrl(), 255);
        paymentNotificationRouteService.validateRoutedBaseUrl(notifyUrl);
        paymentNotificationRouteService.validateRoutedBaseUrl(refundNotifyUrl);
        PaymentVerifyMode verifyMode = parseVerifyMode(request.verifyMode());
        String wechatPublicKeyId = requireTextOrExisting(
                request.wechatPublicKeyId(), 128, existing == null ? null : existing.wechatPublicKeyId());

        String apiV3Key = trimToNull(request.apiV3Key());
        if (apiV3Key == null && existing == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (apiV3Key != null) {
            apiV3Key = validateApiV3Key(apiV3Key);
        }

        String privateKeyPem = null;
        if (StringUtils.hasText(request.privateKeyPem())) {
            privateKeyPem = paymentPemValidator.validatePrivateKey(request.privateKeyPem());
        } else if (existing == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        String publicKeyPem = null;
        if (StringUtils.hasText(request.wechatPublicKeyPem())) {
            publicKeyPem = paymentPemValidator.validatePublicKey(request.wechatPublicKeyPem());
        } else if (existing == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        return new ValidatedConfig(
                configName, appId, mchId, merchantSerialNo, apiV3Key, privateKeyPem,
                verifyMode, wechatPublicKeyId, publicKeyPem, notifyUrl, refundNotifyUrl);
    }

    private AdminPaymentConfigResponse requireConfig(Long configId) {
        return jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                               verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                               enabled, status, created_at, updated_at
                        from payment_config
                        where id = :configId and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .query(this::mapResponse)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private PaymentConfigRow requireConfigRow(Long configId, boolean forUpdate) {
        return requireConfigRow(configId, forUpdate, ErrorCode.VALIDATION_FAILED);
    }

    private PaymentConfigRow requireConfigRow(
            Long configId,
            boolean forUpdate,
            ErrorCode unavailableError
    ) {
        if (configId == null) {
            throw new BusinessException(unavailableError);
        }
        return jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                               verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                               enabled, updated_at, secret_cipher_version, secret_key_id, secret_revision
                        from payment_config
                        where id = :configId and status = 'ACTIVE'
                        """ + (forUpdate ? " for update" : ""))
                .param("configId", configId)
                .query((rs, rowNum) -> new PaymentConfigRow(
                        rs.getLong("id"),
                        rs.getString("config_name"),
                        rs.getString("app_id"),
                        rs.getString("mch_id"),
                        rs.getString("merchant_serial_no"),
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getString("private_key_pem_ciphertext"),
                        rs.getString("wechat_public_key_pem_ciphertext"),
                        PaymentVerifyMode.valueOf(rs.getString("verify_mode")),
                        rs.getString("wechat_public_key_id"),
                        rs.getString("notify_url"),
                        rs.getString("refund_notify_url"),
                        rs.getBoolean("enabled"),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id"),
                        rs.getLong("secret_revision")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(unavailableError));
    }

    private AdminPaymentConfigResponse mapResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminPaymentConfigResponse(
                rs.getLong("id"),
                rs.getString("config_name"),
                mask(rs.getString("app_id"), 3, 3),
                mask(rs.getString("mch_id"), 2, 2),
                mask(rs.getString("merchant_serial_no"), 3, 3),
                StringUtils.hasText(rs.getString("api_v3_key_ciphertext")),
                StringUtils.hasText(rs.getString("private_key_pem_ciphertext")),
                rs.getString("verify_mode"),
                mask(rs.getString("wechat_public_key_id"), 4, 4),
                StringUtils.hasText(rs.getString("wechat_public_key_pem_ciphertext")),
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

    private String requireTextOrExisting(String value, int maxLength, String existingValue) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return requireText(existingValue, maxLength);
        }
        String required = requireText(trimmed, maxLength);
        rejectMaskedPlaceholder(required);
        return required;
    }

    private void rejectMaskedPlaceholder(String value) {
        if (value.contains("*")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String validateApiV3Key(String value) {
        String normalized = trimToNull(value);
        if (normalized == null
                || !normalized.equals(value)
                || normalized.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        rejectMaskedPlaceholder(normalized);
        return normalized;
    }

    private PaymentVerifyMode parseVerifyMode(String verifyMode) {
        String value = trimToNull(verifyMode);
        if (value == null) {
            return PaymentVerifyMode.PUBLIC_KEY;
        }
        try {
            PaymentVerifyMode parsed = PaymentVerifyMode.valueOf(value);
            if (parsed != PaymentVerifyMode.PUBLIC_KEY) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PaymentConfigRow(
            Long id,
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3KeyCiphertext,
            String privateKeyPemCiphertext,
            String wechatPublicKeyPemCiphertext,
            PaymentVerifyMode verifyMode,
            String wechatPublicKeyId,
            String notifyUrl,
            String refundNotifyUrl,
            boolean enabled,
            LocalDateTime updatedAt,
            int secretCipherVersion,
            String secretKeyId,
            long secretRevision
    ) {
    }

    private record StoredConfigSnapshot(PaymentConfigRow config, ResolvedPaymentConfig resolved) {
    }

    private record ValidatedConfig(
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3Key,
            String privateKeyPem,
            PaymentVerifyMode verifyMode,
            String wechatPublicKeyId,
            String wechatPublicKeyPem,
            String notifyUrl,
            String refundNotifyUrl
    ) {
        @Override
        public String toString() {
            return "ValidatedConfig[configName=" + configName
                    + ", appIdConfigured=" + StringUtils.hasText(appId)
                    + ", mchIdConfigured=" + StringUtils.hasText(mchId)
                    + ", merchantSerialNoConfigured=" + StringUtils.hasText(merchantSerialNo)
                    + ", apiV3KeyConfigured=" + StringUtils.hasText(apiV3Key)
                    + ", privateKeyPemConfigured=" + StringUtils.hasText(privateKeyPem)
                    + ", verifyMode=" + verifyMode
                    + ", wechatPublicKeyIdConfigured=" + StringUtils.hasText(wechatPublicKeyId)
                    + ", wechatPublicKeyPemConfigured=" + StringUtils.hasText(wechatPublicKeyPem)
                    + ", notifyUrlConfigured=" + StringUtils.hasText(notifyUrl)
                    + ", refundNotifyUrlConfigured=" + StringUtils.hasText(refundNotifyUrl) + "]";
        }
    }

    private record EncryptedMaterial(
            PaymentSecretCipher.EncryptedSecret apiV3Key,
            PaymentSecretCipher.EncryptedSecret privateKeyPem,
            PaymentSecretCipher.EncryptedSecret wechatPublicKeyPem
    ) {
    }
}
