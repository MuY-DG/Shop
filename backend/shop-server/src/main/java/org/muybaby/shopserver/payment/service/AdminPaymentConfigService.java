package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentConfigSourceSettingService;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigRequest;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.EffectivePaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.EnvironmentPaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.PaymentConfigSourceResponse;
import org.muybaby.shopserver.payment.dto.PaymentConfigSourceUpdateRequest;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService.PaymentSecretSnapshot;
import org.muybaby.shopserver.storage.service.StorageUsageService;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final PaymentConfigSourceSettingService paymentConfigSourceSettingService;
    private final PaymentNotificationRouteService paymentNotificationRouteService;
    private final PaymentSecretCipher paymentSecretCipher;
    private final PrivateStorageFileService privateStorageFileService;
    private final StorageUsageService storageUsageService;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public AdminPaymentConfigService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PaymentConfigResolver paymentConfigResolver,
            PaymentConfigSourceSettingService paymentConfigSourceSettingService,
            PaymentNotificationRouteService paymentNotificationRouteService,
            PaymentSecretCipher paymentSecretCipher,
            PrivateStorageFileService privateStorageFileService,
            StorageUsageService storageUsageService,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentConfigSourceSettingService = paymentConfigSourceSettingService;
        this.paymentNotificationRouteService = paymentNotificationRouteService;
        this.paymentSecretCipher = paymentSecretCipher;
        this.privateStorageFileService = privateStorageFileService;
        this.storageUsageService = storageUsageService;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public EffectivePaymentConfigResponse effective() {
        return toEffectiveResponse(paymentConfigResolver.resolve());
    }

    public EnvironmentPaymentConfigResponse environment() {
        try {
            return new EnvironmentPaymentConfigResponse(
                    true,
                    toEffectiveResponse(paymentConfigResolver.resolve(PaymentConfigSource.ENV))
            );
        } catch (BusinessException ex) {
            if (ex.errorCode() != ErrorCode.VALIDATION_FAILED) {
                throw ex;
            }
            return new EnvironmentPaymentConfigResponse(false, null);
        }
    }

    private EffectivePaymentConfigResponse toEffectiveResponse(ResolvedPaymentConfig config) {
        return new EffectivePaymentConfigResponse(
                config.configId(),
                config.source().name(),
                config.configName(),
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

    public PaymentConfigSourceResponse source() {
        return paymentConfigSourceSettingService.current();
    }

    public PaymentConfigSourceResponse updateSource(PaymentConfigSourceUpdateRequest request) {
        return outsideTransaction(() -> {
            if (request == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            PaymentConfigSource source = paymentConfigSourceSettingService.parse(request.source());
            ResolvedPaymentConfig resolved = paymentConfigResolver.resolve(source);
            requireNotificationRouteReady(resolved);
            StoredConfigSnapshot storedConfig = resolved.source() == PaymentConfigSource.DB
                    ? inspectStoredConfig(resolved.configId(), true, resolved)
                    : null;
            return requireTransactionResult(requiresNewTransaction.execute(status -> {
                if (storedConfig != null) {
                    revalidateStoredConfig(storedConfig, false);
                }
                paymentConfigSourceSettingService.update(source);
                return paymentConfigSourceSettingService.current();
            }));
        });
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

    public AdminPaymentConfigResponse create(AdminPaymentConfigRequest request) {
        return outsideTransaction(() -> {
            ValidatedConfig validated = validateRequest(request, null);
            List<Long> currentSecretIds = paymentSecretIds(validated);
            List<PaymentSecretSnapshot> secretSnapshots =
                    privateStorageFileService.inspectPaymentSecrets(currentSecretIds);
            return requireTransactionResult(requiresNewTransaction.execute(status ->
                    createInTransaction(validated, currentSecretIds, secretSnapshots)));
        });
    }

    private AdminPaymentConfigResponse createInTransaction(
            ValidatedConfig validated,
            List<Long> currentSecretIds,
            List<PaymentSecretSnapshot> secretSnapshots
    ) {
        privateStorageFileService.lockAndRevalidatePaymentSecrets(secretSnapshots, List.of());
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
                        .addValue("apiV3KeyCiphertext", "")
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
        PaymentSecretCipher.EncryptedSecret encryptedApiV3Key = paymentSecretCipher.encrypt(
                PaymentConfigResolver.apiV3KeyContext(configId), validated.apiV3Key());
        int encryptedRows = jdbcClient.sql("""
                        update payment_config
                        set api_v3_key_ciphertext = :ciphertext,
                            secret_cipher_version = :cipherVersion,
                            secret_key_id = :keyId,
                            secret_revision = secret_revision + 1
                        where id = :configId
                          and api_v3_key_ciphertext = ''
                        """)
                .param("ciphertext", encryptedApiV3Key.ciphertext())
                .param("cipherVersion", encryptedApiV3Key.version())
                .param("keyId", encryptedApiV3Key.keyId())
                .param("configId", configId)
                .update();
        if (encryptedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        replaceProtectedUsages(configId, validated);
        privateStorageFileService.reconcilePaymentSecretRetention(currentSecretIds, List.of());
        return requireConfig(configId);
    }

    public AdminPaymentConfigResponse update(Long configId, AdminPaymentConfigRequest request) {
        return outsideTransaction(() -> {
            PaymentConfigRow observed = requireConfigRow(configId, false);
            ValidatedConfig validated = validateRequest(request, observed);
            List<Long> currentSecretIds = paymentSecretIds(validated);
            List<Long> previousSecretIds = paymentSecretIds(observed);
            List<PaymentSecretSnapshot> secretSnapshots =
                    privateStorageFileService.inspectPaymentSecrets(currentSecretIds);
            return requireTransactionResult(requiresNewTransaction.execute(status -> updateInTransaction(
                    configId,
                    observed,
                    validated,
                    currentSecretIds,
                    previousSecretIds,
                    secretSnapshots
            )));
        });
    }

    private AdminPaymentConfigResponse updateInTransaction(
            Long configId,
            PaymentConfigRow observed,
            ValidatedConfig validated,
            List<Long> currentSecretIds,
            List<Long> previousSecretIds,
            List<PaymentSecretSnapshot> secretSnapshots
    ) {
        privateStorageFileService.lockAndRevalidatePaymentSecrets(secretSnapshots, previousSecretIds);
        PaymentConfigRow locked = requireConfigRow(configId, true);
        requireUnchangedConfig(observed, locked);
        rejectReferencedConfigMutation(configId);
        boolean secretChanged = validated.apiV3Key() != null;
        PaymentSecretCipher.EncryptedSecret encryptedApiV3Key = secretChanged
                ? paymentSecretCipher.encrypt(
                        PaymentConfigResolver.apiV3KeyContext(configId), validated.apiV3Key())
                : new PaymentSecretCipher.EncryptedSecret(
                        locked.apiV3KeyCiphertext(),
                        locked.secretCipherVersion(),
                        locked.secretKeyId());
        int updatedRows = jdbcClient.sql("""
                        update payment_config
                        set config_name = :configName,
                            app_id = :appId,
                            mch_id = :mchId,
                            merchant_serial_no = :merchantSerialNo,
                            api_v3_key_ciphertext = :apiV3KeyCiphertext,
                            secret_cipher_version = :secretCipherVersion,
                            secret_key_id = :secretKeyId,
                            secret_revision = secret_revision
                                + case when :secretChanged then 1 else 0 end,
                            secret_reencrypted_at = case
                                when :secretChanged then null
                                else secret_reencrypted_at
                            end,
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
                .param("apiV3KeyCiphertext", encryptedApiV3Key.ciphertext())
                .param("secretCipherVersion", encryptedApiV3Key.version())
                .param("secretKeyId", encryptedApiV3Key.keyId())
                .param("secretChanged", secretChanged)
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
        privateStorageFileService.reconcilePaymentSecretRetention(currentSecretIds, previousSecretIds);
        return requireConfig(configId);
    }

    private void rejectReferencedConfigMutation(Long configId) {
        boolean referenced = jdbcClient.sql("""
                        select count(*)
                        from (
                            select id
                            from payment_order
                            where payment_config_id = :configId
                            union all
                            select id
                            from purged_payment_identity
                            where payment_config_id = :configId
                            union all
                            select id
                            from purged_refund_identity
                            where payment_config_id = :configId
                        ) referenced_payment_config
                        """)
                .param("configId", configId)
                .query(Long.class)
                .single() > 0;
        if (referenced) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    public AdminPaymentConfigResponse enable(Long configId) {
        return outsideTransaction(() -> {
            StoredConfigSnapshot storedConfig = inspectStoredConfig(configId, false, null);
            return requireTransactionResult(requiresNewTransaction.execute(status ->
                    enableInTransaction(configId, storedConfig)));
        });
    }

    private AdminPaymentConfigResponse enableInTransaction(
            Long configId,
            StoredConfigSnapshot storedConfig
    ) {
        revalidateStoredConfig(storedConfig, true);
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
        List<PaymentSecretSnapshot> secretSnapshots = privateStorageFileService.inspectPaymentSecrets(
                paymentSecretIds(confirmed)
        );
        return new StoredConfigSnapshot(confirmed, secretSnapshots);
    }

    private void revalidateStoredConfig(StoredConfigSnapshot storedConfig, boolean lockAllConfigs) {
        privateStorageFileService.lockAndRevalidatePaymentSecrets(storedConfig.secretSnapshots(), List.of());
        if (lockAllConfigs) {
            lockActiveConfigsInOrder();
        }
        PaymentConfigRow locked = requireConfigRow(storedConfig.config().id(), true);
        requireUnchangedConfig(storedConfig.config(), locked);
    }

    private void lockActiveConfigsInOrder() {
        jdbcClient.sql("""
                        select id
                        from payment_config
                        where status = 'ACTIVE'
                        order by id
                        for update
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
                && Objects.equals(decryptApiV3Key(row), resolved.apiV3Key())
                && row.verifyMode() == resolved.verifyMode()
                && Objects.equals(row.wechatPublicKeyId(), resolved.wechatPublicKeyId())
                && Objects.equals(row.privateKeyFileId(), resolved.privateKeyFileId())
                && Objects.equals(row.merchantCertificateFileId(), resolved.merchantCertificateFileId())
                && Objects.equals(row.wechatPublicKeyFileId(), resolved.wechatPublicKeyFileId())
                && Objects.equals(row.notifyUrl(), resolved.notifyUrl())
                && Objects.equals(row.refundNotifyUrl(), resolved.refundNotifyUrl());
        if (!matches) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireNotificationRouteReady(ResolvedPaymentConfig resolved) {
        if (resolved == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        paymentNotificationRouteService.validateRoutedBaseUrl(resolved.notifyUrl());
        paymentNotificationRouteService.validateRoutedBaseUrl(resolved.refundNotifyUrl());
    }

    private String decryptApiV3Key(PaymentConfigRow row) {
        PaymentSecretCipher.DecryptedSecret decrypted = paymentSecretCipher.decrypt(
                PaymentConfigResolver.apiV3KeyContext(row.id()), row.apiV3KeyCiphertext());
        if (decrypted.version() != row.secretCipherVersion()
                || !decrypted.keyId().equals(row.secretKeyId() == null ? "" : row.secretKeyId())) {
            throw new BusinessException(ErrorCode.PAYMENT_CONFIGURATION_CHANGED);
        }
        return decrypted.plaintext();
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
                request.merchantSerialNo(),
                128,
                existing == null ? null : existing.merchantSerialNo()
        );
        String notifyUrl = requireText(request.notifyUrl(), 255);
        String refundNotifyUrl = requireText(request.refundNotifyUrl(), 255);
        paymentNotificationRouteService.validateRoutedBaseUrl(notifyUrl);
        paymentNotificationRouteService.validateRoutedBaseUrl(refundNotifyUrl);
        PaymentVerifyMode verifyMode = parseVerifyMode(request.verifyMode());
        Long privateKeyFileId = requireFileId(request.privateKeyFileId());
        Long merchantCertificateFileId = optionalFileId(request.merchantCertificateFileId());
        String wechatPublicKeyId = textOrExisting(
                request.wechatPublicKeyId(),
                128,
                existing == null ? null : existing.wechatPublicKeyId()
        );
        Long wechatPublicKeyFileId = optionalFileId(request.wechatPublicKeyFileId());

        if (verifyMode == PaymentVerifyMode.PUBLIC_KEY) {
            wechatPublicKeyId = requireText(wechatPublicKeyId, 128);
            wechatPublicKeyFileId = requireFileId(wechatPublicKeyFileId);
        }

        String apiV3Key = trimToNull(request.apiV3Key());
        if (apiV3Key == null) {
            if (existing == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } else {
            rejectMaskedPlaceholder(apiV3Key);
        }

        return new ValidatedConfig(
                configName,
                appId,
                mchId,
                merchantSerialNo,
                apiV3Key,
                privateKeyFileId,
                merchantCertificateFileId,
                verifyMode,
                wechatPublicKeyId,
                wechatPublicKeyFileId,
                notifyUrl,
                refundNotifyUrl
        );
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

    private PaymentConfigRow requireConfigRow(Long configId, boolean forUpdate) {
        if (configId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return jdbcClient.sql("""
                        select id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                               private_key_file_id, merchant_certificate_file_id, verify_mode,
                               wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                               enabled, updated_at, secret_cipher_version, secret_key_id
                        from payment_config
                        where id = :configId
                          and status = 'ACTIVE'
                        """ + (forUpdate ? " for update" : ""))
                .param("configId", configId)
                .query((rs, rowNum) -> new PaymentConfigRow(
                        rs.getLong("id"),
                        rs.getString("config_name"),
                        rs.getString("app_id"),
                        rs.getString("mch_id"),
                        rs.getString("merchant_serial_no"),
                        rs.getString("api_v3_key_ciphertext"),
                        nullableLong(rs, "private_key_file_id"),
                        nullableLong(rs, "merchant_certificate_file_id"),
                        PaymentVerifyMode.valueOf(rs.getString("verify_mode")),
                        rs.getString("wechat_public_key_id"),
                        nullableLong(rs, "wechat_public_key_file_id"),
                        rs.getString("notify_url"),
                        rs.getString("refund_notify_url"),
                        rs.getBoolean("enabled"),
                        rs.getObject("updated_at", LocalDateTime.class),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private List<Long> paymentSecretIds(ValidatedConfig config) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        addIfPresent(ids, config.privateKeyFileId());
        addIfPresent(ids, config.merchantCertificateFileId());
        addIfPresent(ids, config.wechatPublicKeyFileId());
        return new ArrayList<>(ids);
    }

    private List<Long> paymentSecretIds(PaymentConfigRow config) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        addIfPresent(ids, config.privateKeyFileId());
        addIfPresent(ids, config.merchantCertificateFileId());
        addIfPresent(ids, config.wechatPublicKeyFileId());
        return new ArrayList<>(ids);
    }

    private void addIfPresent(LinkedHashSet<Long> ids, Long assetId) {
        if (assetId != null) {
            ids.add(assetId);
        }
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

    private String requireTextOrExisting(String value, int maxLength, String existingValue) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return requireText(existingValue, maxLength);
        }
        String required = requireText(trimmed, maxLength);
        rejectMaskedPlaceholder(required);
        return required;
    }

    private String textOrExisting(String value, int maxLength, String existingValue) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return trimToEmpty(existingValue);
        }
        String required = requireText(trimmed, maxLength);
        rejectMaskedPlaceholder(required);
        return required;
    }

    private Long requireFileId(Long fileId) {
        if (fileId == null || fileId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return fileId;
    }

    private Long optionalFileId(Long fileId) {
        if (fileId != null && fileId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return fileId;
    }

    private void rejectMaskedPlaceholder(String value) {
        if (value.contains("*")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
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
            boolean enabled,
            LocalDateTime updatedAt,
            int secretCipherVersion,
            String secretKeyId
    ) {
    }

    private record StoredConfigSnapshot(
            PaymentConfigRow config,
            List<PaymentSecretSnapshot> secretSnapshots
    ) {
    }

    private record ValidatedConfig(
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3Key,
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
