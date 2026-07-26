package org.muybaby.shopserver.storage.compression.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.storage.compression.dto.AdminImageCompressionConfigRequest;
import org.muybaby.shopserver.storage.compression.dto.AdminImageCompressionConfigResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class ImageCompressionRuntimeConfigService {

    private static final long SETTING_ID = 1L;
    private static final int DEFAULT_MONTHLY_LIMIT = 500;
    private static final int MAX_MONTHLY_LIMIT = 10_000_000;
    private static final int MAX_API_KEY_LENGTH = 256;
    private static final String OUTPUT_FORMAT = "WEBP";
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(2);

    private final ImageCompressionProperties properties;
    private final JdbcClient jdbcClient;
    private final PaymentSecretCipher secretCipher;
    private final ObjectProvider<ImageCompressionUsageProbe> usageProbeProvider;

    public ImageCompressionRuntimeConfigService(
            ImageCompressionProperties properties,
            JdbcClient jdbcClient,
            PaymentSecretCipher secretCipher,
            ObjectProvider<ImageCompressionUsageProbe> usageProbeProvider
    ) {
        this.properties = properties;
        this.jdbcClient = jdbcClient;
        this.secretCipher = secretCipher;
        this.usageProbeProvider = usageProbeProvider;
        requireMonthlyLimit(properties.effectiveMonthlyLimit());
    }

    public ResolvedImageCompressionConfig effective() {
        Optional<ImageCompressionSettingRow> persisted = persistedRow();
        return resolve(persisted.orElse(null), currentQuotaPeriod());
    }

    public AdminImageCompressionConfigResponse current() {
        Optional<ImageCompressionSettingRow> persisted = persistedRow();
        ResolvedImageCompressionConfig config = resolve(
                persisted.orElse(null), currentQuotaPeriod());
        return response(config, persisted.orElse(null));
    }

    /**
     * Atomically reserves the provider operations needed by one upload. Active reservations
     * are included in the local monthly budget so concurrent uploads cannot all pass the final
     * remaining-count check. Abandoned reservations expire after the maximum provider call window.
     */
    @Transactional
    public CompressionPermit acquireCompressionPermit(int expectedCost) {
        if (expectedCost <= 0 || expectedCost > 2) {
            throw validationFailure();
        }
        ensurePersistedDefaults();
        ImageCompressionSettingRow row = persistedRowForUpdate();
        YearMonth period = currentQuotaPeriod();
        ResolvedImageCompressionConfig config = resolve(row, period);
        if (!config.enabled()) {
            return null;
        }

        jdbcClient.sql("""
                        delete from image_compression_reservation
                        where expires_at <= current_timestamp
                        """)
                .update();

        String fingerprint = apiKeyFingerprint(config.apiKey());
        Long reserved = jdbcClient.sql("""
                        select coalesce(sum(reserved_count), 0)
                        from image_compression_reservation
                        where setting_id = :settingId
                          and usage_key_fingerprint = :fingerprint
                          and quota_period = :quotaPeriod
                        """)
                .param("settingId", SETTING_ID)
                .param("fingerprint", fingerprint)
                .param("quotaPeriod", period.toString())
                .query(Long.class)
                .single();
        long activeReservations = reserved == null ? 0 : reserved;
        if (config.remainingCount() < expectedCost) {
            markQuotaExhausted(config.apiKey());
            return null;
        }
        if (activeReservations > config.remainingCount() - expectedCost) {
            return null;
        }

        String reservationId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = databaseNow().plus(RESERVATION_TTL);
        jdbcClient.sql("""
                        insert into image_compression_reservation
                            (id, setting_id, usage_key_fingerprint, quota_period,
                             reserved_count, expires_at)
                        values
                            (:id, :settingId, :fingerprint, :quotaPeriod,
                             :reservedCount, :expiresAt)
                        """)
                .param("id", reservationId)
                .param("settingId", SETTING_ID)
                .param("fingerprint", fingerprint)
                .param("quotaPeriod", period.toString())
                .param("reservedCount", expectedCost)
                .param("expiresAt", expiresAt)
                .update();
        return new CompressionPermit(reservationId, config);
    }

    @Transactional
    public void releaseCompressionPermit(String reservationId) {
        if (!StringUtils.hasText(reservationId)) {
            return;
        }
        jdbcClient.sql("""
                        delete from image_compression_reservation
                        where id = :id
                        """)
                .param("id", reservationId)
                .update();
    }

    @Transactional
    public AdminImageCompressionConfigResponse update(
            AdminImageCompressionConfigRequest request
    ) {
        if (request == null || request.requestedEnabled() == null) {
            throw validationFailure();
        }

        ensurePersistedDefaults();
        ImageCompressionSettingRow existingRow = persistedRowForUpdate();
        ImageCompressionConfigSource currentSource = existingRow.adminConfigured()
                ? parseSource(existingRow.configSource())
                : properties.effectiveConfigSource();
        int currentMonthlyLimit = existingRow.adminConfigured()
                ? existingRow.monthlyLimit()
                : requireMonthlyLimit(properties.effectiveMonthlyLimit());
        ImageCompressionConfigSource configSource = parseSource(request.configSource());
        int monthlyLimit = requireMonthlyLimit(request.monthlyLimit());

        String submittedKey = normalizeApiKey(request.apiKey());
        String environmentKey = environmentKey();
        boolean dbKeyRequired = request.requestedEnabled()
                && (configSource == ImageCompressionConfigSource.DB
                || (configSource == ImageCompressionConfigSource.AUTO
                && !StringUtils.hasText(environmentKey)));
        String selectedDbKey = submittedKey;
        if (!StringUtils.hasText(selectedDbKey) && dbKeyRequired) {
            selectedDbKey = decryptDbKey(existingRow);
        }
        KeySelection selection = selectKey(configSource, environmentKey, selectedDbKey);
        if (request.requestedEnabled() && !StringUtils.hasText(selection.apiKey())) {
            throw validationFailure();
        }

        PaymentSecretCipher.EncryptedSecret encrypted = StringUtils.hasText(submittedKey)
                ? secretCipher.encrypt(secretContext(), submittedKey)
                : null;
        int cipherVersion = encrypted == null
                ? existingRow.secretCipherVersion()
                : encrypted.version();
        String keyId = encrypted == null
                ? normalize(existingRow.secretKeyId())
                : encrypted.keyId();
        String ciphertext = encrypted == null
                ? normalize(existingRow.apiKeyCiphertext())
                : encrypted.ciphertext();

        YearMonth period = currentQuotaPeriod();
        boolean identityChanged = configSource != currentSource
                || StringUtils.hasText(submittedKey);
        boolean samePeriod = periodEquals(existingRow.quotaPeriod(), period);
        int providerCount = samePeriod && !identityChanged
                ? existingRow.providerCount()
                : 0;
        boolean providerCountKnown = samePeriod
                && !identityChanged
                && existingRow.providerCountKnown();
        String usageKeyFingerprint = samePeriod && !identityChanged
                ? normalize(existingRow.usageKeyFingerprint())
                : "";
        LocalDateTime lastCheckedAt = samePeriod && !identityChanged
                ? existingRow.lastCheckedAt()
                : null;
        boolean budgetRaised = monthlyLimit > currentMonthlyLimit;
        ImageCompressionAutoDisabledReason reason = samePeriod && !identityChanged
                ? parseReason(existingRow.autoDisabledReason())
                : null;
        if (providerCount >= monthlyLimit) {
            reason = ImageCompressionAutoDisabledReason.QUOTA_EXHAUSTED;
        } else if (identityChanged
                || (budgetRaised
                && reason == ImageCompressionAutoDisabledReason.QUOTA_EXHAUSTED)) {
            reason = null;
        }

        int updatedRows = jdbcClient.sql("""
                        update image_compression_runtime_setting
                        set admin_configured = true,
                            requested_enabled = :requestedEnabled,
                            config_source = :configSource,
                            api_key_ciphertext = :ciphertext,
                            monthly_limit = :monthlyLimit,
                            provider_count = :providerCount,
                            provider_count_known = :providerCountKnown,
                            usage_key_fingerprint = :usageKeyFingerprint,
                            quota_period = :quotaPeriod,
                            last_checked_at = :lastCheckedAt,
                            auto_disabled_reason = :autoDisabledReason,
                            secret_cipher_version = :cipherVersion,
                            secret_key_id = :keyId,
                            secret_revision = secret_revision + 1,
                            secret_reencrypted_at = null,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("requestedEnabled", request.requestedEnabled())
                .param("configSource", configSource.name())
                .param("ciphertext", ciphertext)
                .param("monthlyLimit", monthlyLimit)
                .param("providerCount", providerCount)
                .param("providerCountKnown", providerCountKnown)
                .param("usageKeyFingerprint", usageKeyFingerprint)
                .param("quotaPeriod", period.toString())
                .param("lastCheckedAt", lastCheckedAt)
                .param("autoDisabledReason", reasonName(reason))
                .param("cipherVersion", cipherVersion)
                .param("keyId", keyId)
                .param("id", SETTING_ID)
                .update();
        if (updatedRows != 1) {
            throw validationFailure();
        }
        if (identityChanged) {
            jdbcClient.sql("""
                            delete from image_compression_reservation
                            where setting_id = :settingId
                            """)
                    .param("settingId", SETTING_ID)
                    .update();
        }
        return current();
    }

    public AdminImageCompressionConfigResponse refreshUsage() {
        ResolvedImageCompressionConfig config = effective();
        if (!StringUtils.hasText(config.apiKey())) {
            throw validationFailure();
        }
        ImageCompressionUsageProbe usageProbe = usageProbeProvider.getIfAvailable();
        if (usageProbe == null) {
            throw new IllegalStateException("Image compression usage probe is not available");
        }
        ImageCompressionUsageProbe.ProbeResult result = usageProbe.probe(config.apiKey());
        if (result == null) {
            throw validationFailure();
        }
        switch (result.state()) {
            case VALID -> {
                if (result.count() == null) {
                    throw validationFailure();
                }
                recordProviderCount(config.apiKey(), result.count());
            }
            case QUOTA_EXHAUSTED -> {
                if (result.count() != null) {
                    recordProviderCount(config.apiKey(), result.count());
                }
                markQuotaExhausted(config.apiKey());
            }
            case INVALID_KEY -> markInvalidKey(config.apiKey());
            case RATE_LIMITED -> throw validationFailure();
        }
        return current();
    }

    @Transactional
    public void recordProviderCount(String apiKey, int providerCount) {
        recordProviderCount(apiKey, providerCount, currentQuotaPeriod());
    }

    /**
     * Records the provider's monotonic account count. Within a period concurrent or stale
     * observations for the same key can only move the stored value upward. Results from an
     * in-flight request using a key that is no longer active are ignored.
     */
    @Transactional
    public void recordProviderCount(
            String apiKey,
            int providerCount,
            YearMonth quotaPeriod
    ) {
        if (providerCount < 0 || quotaPeriod == null) {
            throw validationFailure();
        }
        LockedConfig locked = lockMatchingConfig(apiKey, quotaPeriod);
        if (locked == null) {
            return;
        }
        String fingerprint = apiKeyFingerprint(apiKey);
        jdbcClient.sql("""
                        update image_compression_runtime_setting
                        set auto_disabled_reason =
                                case
                                    when (
                                        quota_period <> :quotaPeriod
                                        or usage_key_fingerprint <> :fingerprint
                                    ) and :providerCount >= :monthlyLimit
                                        then 'QUOTA_EXHAUSTED'
                                    when quota_period <> :quotaPeriod
                                         or usage_key_fingerprint <> :fingerprint
                                        then ''
                                    when greatest(provider_count, :providerCount) >= :monthlyLimit
                                        then 'QUOTA_EXHAUSTED'
                                    when auto_disabled_reason in (
                                        'INVALID_KEY', 'QUOTA_EXHAUSTED'
                                    )
                                        then ''
                                    else auto_disabled_reason
                                end,
                            provider_count =
                                case
                                    when quota_period = :quotaPeriod
                                         and usage_key_fingerprint = :fingerprint
                                        then greatest(provider_count, :providerCount)
                                    else :providerCount
                                end,
                            provider_count_known = true,
                            usage_key_fingerprint = :fingerprint,
                            quota_period = :quotaPeriod,
                            last_checked_at = current_timestamp
                        where id = :id
                        """)
                .param("providerCount", providerCount)
                .param("monthlyLimit", locked.config().monthlyLimit())
                .param("fingerprint", fingerprint)
                .param("quotaPeriod", quotaPeriod.toString())
                .param("id", SETTING_ID)
                .update();
    }

    @Transactional
    public void markQuotaExhausted(String apiKey) {
        YearMonth period = currentQuotaPeriod();
        LockedConfig locked = lockMatchingConfig(apiKey, period);
        if (locked == null) {
            return;
        }
        String fingerprint = apiKeyFingerprint(apiKey);
        jdbcClient.sql("""
                        update image_compression_runtime_setting
                        set provider_count =
                                case
                                    when quota_period = :quotaPeriod
                                         and usage_key_fingerprint = :fingerprint
                                        then provider_count
                                    else 0
                                end,
                            provider_count_known =
                                case
                                    when quota_period = :quotaPeriod
                                         and usage_key_fingerprint = :fingerprint
                                        then provider_count_known
                                    else false
                                end,
                            usage_key_fingerprint = :fingerprint,
                            quota_period = :quotaPeriod,
                            last_checked_at = current_timestamp,
                            auto_disabled_reason = 'QUOTA_EXHAUSTED'
                        where id = :id
                        """)
                .param("fingerprint", fingerprint)
                .param("quotaPeriod", period.toString())
                .param("id", SETTING_ID)
                .update();
    }

    @Transactional
    public void markInvalidKey(String apiKey) {
        YearMonth period = currentQuotaPeriod();
        LockedConfig locked = lockMatchingConfig(apiKey, period);
        if (locked == null) {
            return;
        }
        String fingerprint = apiKeyFingerprint(apiKey);
        jdbcClient.sql("""
                        update image_compression_runtime_setting
                        set provider_count =
                                case
                                    when quota_period = :quotaPeriod
                                         and usage_key_fingerprint = :fingerprint
                                        then provider_count
                                    else 0
                                end,
                            provider_count_known =
                                case
                                    when quota_period = :quotaPeriod
                                         and usage_key_fingerprint = :fingerprint
                                        then provider_count_known
                                    else false
                                end,
                            usage_key_fingerprint = :fingerprint,
                            quota_period = :quotaPeriod,
                            last_checked_at = current_timestamp,
                            auto_disabled_reason = 'INVALID_KEY'
                        where id = :id
                        """)
                .param("fingerprint", fingerprint)
                .param("quotaPeriod", period.toString())
                .param("id", SETTING_ID)
                .update();
    }

    public static PaymentSecretCipher.SecretContext secretContext() {
        return new PaymentSecretCipher.SecretContext(
                "image-compression-runtime-setting",
                Long.toString(SETTING_ID),
                "api-key"
        );
    }

    private void ensurePersistedDefaults() {
        jdbcClient.sql("""
                        insert into image_compression_runtime_setting
                            (id, admin_configured, requested_enabled,
                             config_source, monthly_limit)
                        values
                            (:id, false, :requestedEnabled, :configSource, :monthlyLimit)
                        on duplicate key update id = id
                        """)
                .param("id", SETTING_ID)
                .param("requestedEnabled", properties.effectiveRequestedEnabled())
                .param("configSource", properties.effectiveConfigSource().name())
                .param("monthlyLimit", requireMonthlyLimit(properties.effectiveMonthlyLimit()))
                .update();
    }

    private Optional<ImageCompressionSettingRow> persistedRow() {
        return jdbcClient.sql("""
                        select admin_configured, requested_enabled, config_source,
                               api_key_ciphertext, monthly_limit, provider_count,
                               provider_count_known, usage_key_fingerprint,
                               quota_period, last_checked_at, auto_disabled_reason,
                               secret_cipher_version, secret_key_id, updated_at
                        from image_compression_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::mapRow)
                .optional();
    }

    private ImageCompressionSettingRow persistedRowForUpdate() {
        return jdbcClient.sql("""
                        select admin_configured, requested_enabled, config_source,
                               api_key_ciphertext, monthly_limit, provider_count,
                               provider_count_known, usage_key_fingerprint,
                               quota_period, last_checked_at, auto_disabled_reason,
                               secret_cipher_version, secret_key_id, updated_at
                        from image_compression_runtime_setting
                        where id = :id
                        for update
                        """)
                .param("id", SETTING_ID)
                .query(this::mapRow)
                .single();
    }

    private LockedConfig lockMatchingConfig(String apiKey, YearMonth quotaPeriod) {
        String expectedFingerprint = apiKeyFingerprint(apiKey);
        if (!StringUtils.hasText(expectedFingerprint)) {
            throw validationFailure();
        }
        ensurePersistedDefaults();
        ImageCompressionSettingRow row = persistedRowForUpdate();
        ResolvedImageCompressionConfig config = resolve(row, quotaPeriod);
        if (!expectedFingerprint.equals(apiKeyFingerprint(config.apiKey()))) {
            return null;
        }
        return new LockedConfig(row, config);
    }

    private ResolvedImageCompressionConfig resolve(
            ImageCompressionSettingRow row,
            YearMonth currentPeriod
    ) {
        boolean adminConfigured = row != null && row.adminConfigured();
        boolean requestedEnabled = adminConfigured
                ? row.requestedEnabled()
                : properties.effectiveRequestedEnabled();
        ImageCompressionConfigSource configSource = adminConfigured
                ? parseSource(row.configSource())
                : properties.effectiveConfigSource();
        int monthlyLimit = requireMonthlyLimit(
                adminConfigured ? row.monthlyLimit() : properties.effectiveMonthlyLimit());
        String environmentKey = environmentKey();
        String dbKey = "";
        boolean invalidDbKey = false;
        if (row != null
                && (configSource == ImageCompressionConfigSource.DB
                || (configSource == ImageCompressionConfigSource.AUTO
                && !StringUtils.hasText(environmentKey)))) {
            try {
                dbKey = decryptDbKey(row);
            } catch (BusinessException ex) {
                invalidDbKey = true;
            }
        }
        KeySelection selection = selectKey(configSource, environmentKey, dbKey);

        boolean sameKeyState = row != null
                && StringUtils.hasText(selection.apiKey())
                && apiKeyFingerprint(selection.apiKey())
                .equals(normalize(row.usageKeyFingerprint()));
        boolean currentState = sameKeyState
                && periodEquals(row.quotaPeriod(), currentPeriod);
        int compressionCount = currentState ? row.providerCount() : 0;
        ImageCompressionAutoDisabledReason storedReason = sameKeyState
                ? parseReason(row.autoDisabledReason())
                : null;
        ImageCompressionAutoDisabledReason reason = currentState
                || storedReason == ImageCompressionAutoDisabledReason.INVALID_KEY
                ? storedReason
                : null;
        if (invalidDbKey && requestedEnabled) {
            reason = ImageCompressionAutoDisabledReason.INVALID_KEY;
        }
        if (compressionCount >= monthlyLimit) {
            reason = ImageCompressionAutoDisabledReason.QUOTA_EXHAUSTED;
        }
        int remainingCount = reason == ImageCompressionAutoDisabledReason.QUOTA_EXHAUSTED
                ? 0
                : Math.max(0, monthlyLimit - compressionCount);
        boolean effectiveEnabled = requestedEnabled
                && StringUtils.hasText(selection.apiKey())
                && reason == null
                && remainingCount > 0;

        return new ResolvedImageCompressionConfig(
                requestedEnabled,
                effectiveEnabled,
                configSource,
                selection.source(),
                selection.apiKey(),
                monthlyLimit,
                compressionCount,
                remainingCount,
                currentPeriod,
                currentState ? row.lastCheckedAt() : null,
                reason
        );
    }

    private AdminImageCompressionConfigResponse response(
            ResolvedImageCompressionConfig config,
            ImageCompressionSettingRow row
    ) {
        boolean providerCountKnown = row != null
                && periodEquals(row.quotaPeriod(), config.quotaPeriod())
                && StringUtils.hasText(config.apiKey())
                && apiKeyFingerprint(config.apiKey())
                .equals(normalize(row.usageKeyFingerprint()))
                && row.providerCountKnown();
        boolean quotaKnownExhausted =
                config.autoDisabledReason() == ImageCompressionAutoDisabledReason.QUOTA_EXHAUSTED;
        return new AdminImageCompressionConfigResponse(
                config.requestedEnabled(),
                config.effectiveEnabled(),
                config.configSource().name(),
                row != null && row.adminConfigured(),
                defaultConfigSource(config).name(),
                StringUtils.hasText(config.apiKey()),
                mask(config.apiKey()),
                OUTPUT_FORMAT,
                false,
                config.monthlyLimit(),
                providerCountKnown ? config.compressionCount() : null,
                providerCountKnown || quotaKnownExhausted ? config.remainingCount() : null,
                providerCountKnown || quotaKnownExhausted ? config.quotaPeriod() : null,
                config.lastCheckedAt(),
                reasonName(config.autoDisabledReason()),
                row == null || !row.adminConfigured() ? null : row.updatedAt()
        );
    }

    private ImageCompressionConfigSource defaultConfigSource(
            ResolvedImageCompressionConfig config
    ) {
        if (config.configSource() == ImageCompressionConfigSource.AUTO) {
            return config.resolvedSource() == null
                    ? ImageCompressionConfigSource.AUTO
                    : config.resolvedSource();
        }
        return properties.effectiveConfigSource();
    }

    private KeySelection selectKey(
            ImageCompressionConfigSource source,
            String environmentKey,
            String databaseKey
    ) {
        return switch (source) {
            case ENV -> new KeySelection(
                    StringUtils.hasText(environmentKey) ? ImageCompressionConfigSource.ENV : null,
                    environmentKey);
            case DB -> new KeySelection(
                    StringUtils.hasText(databaseKey) ? ImageCompressionConfigSource.DB : null,
                    databaseKey);
            case AUTO -> {
                if (StringUtils.hasText(environmentKey)) {
                    yield new KeySelection(ImageCompressionConfigSource.ENV, environmentKey);
                }
                if (StringUtils.hasText(databaseKey)) {
                    yield new KeySelection(ImageCompressionConfigSource.DB, databaseKey);
                }
                yield new KeySelection(null, "");
            }
        };
    }

    private String environmentKey() {
        return normalizeApiKey(properties.apiKey());
    }

    private String decryptDbKey(ImageCompressionSettingRow row) {
        if (!StringUtils.hasText(row.apiKeyCiphertext())) {
            return "";
        }
        PaymentSecretCipher.DecryptedSecret decrypted = secretCipher.decrypt(
                secretContext(), row.apiKeyCiphertext());
        if (decrypted.version() != row.secretCipherVersion()
                || !decrypted.keyId().equals(normalize(row.secretKeyId()))) {
            throw validationFailure();
        }
        return normalizeApiKey(decrypted.plaintext());
    }

    private ImageCompressionConfigSource parseSource(String value) {
        if (!StringUtils.hasText(value)) {
            throw validationFailure();
        }
        try {
            return ImageCompressionConfigSource.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw validationFailure();
        }
    }

    private ImageCompressionAutoDisabledReason parseReason(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return ImageCompressionAutoDisabledReason.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw validationFailure();
        }
    }

    private int requireMonthlyLimit(Integer value) {
        int resolved = value == null ? DEFAULT_MONTHLY_LIMIT : value;
        if (resolved < 1 || resolved > MAX_MONTHLY_LIMIT) {
            throw validationFailure();
        }
        return resolved;
    }

    private String normalizeApiKey(String value) {
        String normalized = normalize(value);
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw validationFailure();
        }
        return normalized;
    }

    private String apiKeyFingerprint(String apiKey) {
        String normalized = normalizeApiKey(apiKey);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private boolean periodEquals(String persistedPeriod, YearMonth period) {
        return period != null && period.toString().equals(normalize(persistedPeriod));
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

    private String reasonName(ImageCompressionAutoDisabledReason reason) {
        return reason == null ? "" : reason.name();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private YearMonth currentQuotaPeriod() {
        return YearMonth.now(ZoneOffset.UTC);
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private ImageCompressionSettingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ImageCompressionSettingRow(
                rs.getBoolean("admin_configured"),
                rs.getBoolean("requested_enabled"),
                rs.getString("config_source"),
                rs.getString("api_key_ciphertext"),
                rs.getInt("monthly_limit"),
                rs.getInt("provider_count"),
                rs.getBoolean("provider_count_known"),
                rs.getString("usage_key_fingerprint"),
                rs.getString("quota_period"),
                timestamp(rs, "last_checked_at"),
                rs.getString("auto_disabled_reason"),
                rs.getInt("secret_cipher_version"),
                rs.getString("secret_key_id"),
                timestamp(rs, "updated_at")
        );
    }

    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private record KeySelection(
            ImageCompressionConfigSource source,
            String apiKey
    ) {
    }

    private record ImageCompressionSettingRow(
            boolean adminConfigured,
            boolean requestedEnabled,
            String configSource,
            String apiKeyCiphertext,
            int monthlyLimit,
            int providerCount,
            boolean providerCountKnown,
            String usageKeyFingerprint,
            String quotaPeriod,
            LocalDateTime lastCheckedAt,
            String autoDisabledReason,
            int secretCipherVersion,
            String secretKeyId,
            LocalDateTime updatedAt
    ) {
    }

    private record LockedConfig(
            ImageCompressionSettingRow row,
            ResolvedImageCompressionConfig config
    ) {
    }

    public record CompressionPermit(
            String reservationId,
            ResolvedImageCompressionConfig config
    ) {
    }
}
