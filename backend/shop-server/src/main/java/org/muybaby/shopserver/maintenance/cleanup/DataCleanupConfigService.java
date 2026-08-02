package org.muybaby.shopserver.maintenance.cleanup;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigUpdateRequest;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupTaskResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupTaskUpdateRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class DataCleanupConfigService {

    private static final long CONFIG_ID = 1L;
    private static final int MIN_BATCH_INTERVAL_SECONDS = 60;
    private static final int MAX_BATCH_INTERVAL_SECONDS = 86_400;
    private static final int MIN_UPLOAD_GRACE_MINUTES = 5;
    private static final int MAX_UPLOAD_GRACE_MINUTES = 10_080;
    private static final Duration EXECUTION_LEASE = Duration.ofMinutes(30);
    private static final int MAX_ERROR_LENGTH = 255;

    private final JdbcClient jdbcClient;

    public DataCleanupConfigService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public DataCleanupConfigResponse current() {
        long revision = jdbcClient.sql("select revision from data_cleanup_config where id = :id")
                .param("id", CONFIG_ID)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Data cleanup config seed is missing"));
        List<DataCleanupTaskSetting> settings = allSettings();
        requireCompleteTaskSet(settings.stream().map(DataCleanupTaskSetting::taskCode).toList());
        return new DataCleanupConfigResponse(
                revision,
                settings.stream().map(this::toResponse).toList()
        );
    }

    @Transactional
    public DataCleanupConfigResponse update(
            DataCleanupConfigUpdateRequest request,
            Long updatedBy
    ) {
        if (request == null || request.revision() == null || request.tasks() == null) {
            throw validationFailure();
        }
        Map<DataCleanupTaskCode, NormalizedUpdate> updates = normalizeUpdates(request.tasks());
        int configUpdated = jdbcClient.sql("""
                        update data_cleanup_config
                        set revision = revision + 1,
                            updated_by = :updatedBy,
                            updated_at = current_timestamp
                        where id = :id
                          and revision = :revision
                        """)
                .param("updatedBy", updatedBy)
                .param("id", CONFIG_ID)
                .param("revision", request.revision())
                .update();
        if (configUpdated != 1) {
            throw new BusinessException(ErrorCode.DATA_CLEANUP_CONFIG_CONFLICT);
        }
        LocalDateTime now = databaseNow();

        for (DataCleanupTaskCode taskCode : DataCleanupTaskCode.values()) {
            NormalizedUpdate update = updates.get(taskCode);
            DataCleanupTaskSetting persisted = update.persisted();
            if (!configChanged(update, persisted)) {
                continue;
            }
            // Completion only updates task rows, not the config header. Lock the changed row
            // after winning the revision CAS so a concurrent completion cannot have its newly
            // calculated catch-up schedule overwritten from the pre-CAS snapshot.
            persisted = requireForUpdate(taskCode);
            update = update.withPersisted(persisted);
            LocalDateTime nextRunAt = nextRunAtAfterUpdate(update, persisted, now);
            int updated = jdbcClient.sql("""
                            update data_cleanup_task_setting
                            set enabled = :enabled,
                                retention_days = :retentionDays,
                                batch_size = :batchSize,
                                cron_expression = :cronExpression,
                                zone_id = :zoneId,
                                batch_interval_seconds = :batchIntervalSeconds,
                                upload_pending_grace_minutes = :uploadPendingGraceMinutes,
                                retain_reviews = :retainReviews,
                                config_revision = :configRevision,
                                next_run_at = :nextRunAt,
                                updated_at = current_timestamp
                            where task_code = :taskCode
                            """)
                    .param("enabled", update.enabled())
                    .param("retentionDays", update.retentionDays())
                    .param("batchSize", update.batchSize())
                    .param("cronExpression", update.cronExpression())
                    .param("zoneId", update.zoneId())
                    .param("batchIntervalSeconds", update.batchIntervalSeconds())
                    .param("uploadPendingGraceMinutes", update.uploadPendingGraceMinutes())
                    .param("retainReviews", update.retainReviews())
                    .param("configRevision", request.revision() + 1)
                    .param("nextRunAt", nextRunAt)
                    .param("taskCode", taskCode.name())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("Data cleanup task seed is missing: " + taskCode);
            }
        }
        return current();
    }

    public DataCleanupTaskSetting require(DataCleanupTaskCode taskCode) {
        if (taskCode == null) {
            throw new IllegalArgumentException("Data cleanup task code is required");
        }
        return jdbcClient.sql(settingSelect() + " where task_code = :taskCode")
                .param("taskCode", taskCode.name())
                .query(this::mapSetting)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Data cleanup task seed is missing: " + taskCode));
    }

    private DataCleanupTaskSetting requireForUpdate(DataCleanupTaskCode taskCode) {
        return jdbcClient.sql(settingSelect() + " where task_code = :taskCode for update")
                .param("taskCode", taskCode.name())
                .query(this::mapSetting)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Data cleanup task seed is missing: " + taskCode));
    }

    @Transactional
    public void initializeMissingSchedules() {
        LocalDateTime now = databaseNow();
        for (DataCleanupTaskSetting setting : allSettings()) {
            if (!setting.enabled() || setting.nextRunAt() != null) {
                continue;
            }
            jdbcClient.sql("""
                            update data_cleanup_task_setting
                            set next_run_at = :nextRunAt,
                                updated_at = current_timestamp
                            where task_code = :taskCode
                              and enabled = true
                              and next_run_at is null
                            """)
                    .param("nextRunAt", nextRunAt(
                            setting.cronExpression(), setting.zoneId(), now))
                    .param("taskCode", setting.taskCode().name())
                    .update();
        }
    }

    public List<DataCleanupTaskCode> dueTaskCodes() {
        return jdbcClient.sql("""
                        select task_code
                        from data_cleanup_task_setting
                        where enabled = true
                          and next_run_at is not null
                          and next_run_at <= current_timestamp
                          and (lease_until is null or lease_until <= current_timestamp)
                        order by next_run_at, task_code
                        """)
                .query(String.class)
                .list()
                .stream()
                .map(DataCleanupTaskCode::valueOf)
                .toList();
    }

    @Transactional
    public Optional<DataCleanupClaim> claim(DataCleanupTaskCode taskCode) {
        LocalDateTime now = databaseNow();
        String leaseToken = UUID.randomUUID().toString();
        int claimed = jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set lease_token = :leaseToken,
                            lease_until = :leaseUntil,
                            run_sequence = run_sequence + 1,
                            last_started_at = current_timestamp,
                            last_status = 'RUNNING',
                            last_error = '',
                            updated_at = current_timestamp
                        where task_code = :taskCode
                          and enabled = true
                          and next_run_at is not null
                          and next_run_at <= :now
                          and (lease_until is null or lease_until <= :now)
                        """)
                .param("leaseToken", leaseToken)
                .param("leaseUntil", now.plus(EXECUTION_LEASE))
                .param("taskCode", taskCode.name())
                .param("now", now)
                .update();
        if (claimed != 1) {
            return Optional.empty();
        }
        return Optional.of(new DataCleanupClaim(require(taskCode), leaseToken));
    }

    @Transactional
    public boolean renewLease(DataCleanupClaim claim) {
        if (claim == null) {
            return false;
        }
        LocalDateTime now = databaseNow();
        return jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set lease_until = :leaseUntil,
                            updated_at = current_timestamp
                        where task_code = :taskCode
                          and lease_token = :leaseToken
                        """)
                .param("leaseUntil", now.plus(EXECUTION_LEASE))
                .param("taskCode", claim.setting().taskCode().name())
                .param("leaseToken", claim.leaseToken())
                .update() == 1;
    }

    @Transactional
    public void complete(DataCleanupClaim claim, int processedCount) {
        if (claim == null) {
            return;
        }
        int normalizedCount = Math.max(0, processedCount);
        LocalDateTime now = databaseNow();
        DataCleanupTaskSetting current = require(claim.setting().taskCode());
        LocalDateTime nextRunAt = null;
        if (current.enabled()) {
            nextRunAt = normalizedCount >= claim.setting().batchSize()
                    ? now.plusSeconds(current.batchIntervalSeconds())
                    : nextRunAt(current.cronExpression(), current.zoneId(), now);
        }
        int updated = jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set next_run_at = :nextRunAt,
                            lease_token = null,
                            lease_until = null,
                            last_completed_at = current_timestamp,
                            last_status = 'SUCCESS',
                            last_processed_count = :processedCount,
                            last_error = '',
                            updated_at = current_timestamp
                        where task_code = :taskCode
                          and lease_token = :leaseToken
                          and config_revision = :configRevision
                        """)
                .param("nextRunAt", nextRunAt)
                .param("processedCount", normalizedCount)
                .param("taskCode", claim.setting().taskCode().name())
                .param("leaseToken", claim.leaseToken())
                .param("configRevision", claim.setting().configRevision())
                .update();
        if (updated == 0) {
            recordOutcomeWithoutRescheduling(
                    claim, "SUCCESS", normalizedCount, "");
        }
    }

    @Transactional
    public void fail(DataCleanupClaim claim, RuntimeException failure) {
        if (claim == null) {
            return;
        }
        LocalDateTime now = databaseNow();
        DataCleanupTaskSetting current = require(claim.setting().taskCode());
        LocalDateTime nextRunAt = current.enabled()
                ? now.plusSeconds(current.batchIntervalSeconds())
                : null;
        String message = failure == null
                ? "Unknown cleanup failure"
                : failure.getClass().getSimpleName() + ": " + Optional.ofNullable(failure.getMessage()).orElse("");
        int updated = jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set next_run_at = :nextRunAt,
                            lease_token = null,
                            lease_until = null,
                            last_completed_at = current_timestamp,
                            last_status = 'FAILED',
                            last_processed_count = 0,
                            last_error = :lastError,
                            updated_at = current_timestamp
                        where task_code = :taskCode
                          and lease_token = :leaseToken
                          and config_revision = :configRevision
                        """)
                .param("nextRunAt", nextRunAt)
                .param("lastError", cleanError(message))
                .param("taskCode", claim.setting().taskCode().name())
                .param("leaseToken", claim.leaseToken())
                .param("configRevision", claim.setting().configRevision())
                .update();
        if (updated == 0) {
            recordOutcomeWithoutRescheduling(
                    claim, "FAILED", 0, cleanError(message));
        }
    }

    static LocalDateTime nextRunAt(String cron, String zoneId, LocalDateTime utcNow) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZonedDateTime businessNow = utcNow.atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(ZoneId.of(zoneId));
            ZonedDateTime next = expression.next(businessNow);
            if (next == null) {
                throw validationFailure();
            }
            return next.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (IllegalArgumentException ex) {
            throw validationFailure();
        }
    }

    private Map<DataCleanupTaskCode, NormalizedUpdate> normalizeUpdates(
            List<DataCleanupTaskUpdateRequest> requests
    ) {
        if (requests == null || requests.size() != DataCleanupTaskCode.values().length) {
            throw validationFailure();
        }
        Map<DataCleanupTaskCode, NormalizedUpdate> updates = new EnumMap<>(DataCleanupTaskCode.class);
        Map<DataCleanupTaskCode, DataCleanupTaskSetting> existing = new EnumMap<>(DataCleanupTaskCode.class);
        for (DataCleanupTaskSetting setting : allSettings()) {
            existing.put(setting.taskCode(), setting);
        }
        for (DataCleanupTaskUpdateRequest request : requests) {
            if (request == null || request.taskCode() == null || updates.containsKey(request.taskCode())) {
                throw validationFailure();
            }
            DataCleanupTaskSetting persisted = existing.get(request.taskCode());
            if (persisted == null) {
                throw validationFailure();
            }
            updates.put(request.taskCode(), normalizeUpdate(request, persisted));
        }
        requireCompleteTaskSet(new ArrayList<>(updates.keySet()));
        return updates;
    }

    private NormalizedUpdate normalizeUpdate(
            DataCleanupTaskUpdateRequest request,
            DataCleanupTaskSetting persisted
    ) {
        DataCleanupTaskCode taskCode = request.taskCode();
        if (request.enabled() == null || request.batchSize() == null
                || request.batchSize() < 1 || request.batchSize() > taskCode.maxBatchSize()
                || request.batchIntervalSeconds() == null
                || request.batchIntervalSeconds() < MIN_BATCH_INTERVAL_SECONDS
                || request.batchIntervalSeconds() > MAX_BATCH_INTERVAL_SECONDS) {
            throw validationFailure();
        }
        Integer retentionDays = request.retentionDays();
        if (taskCode.retentionRequired()) {
            if (retentionDays == null
                    || retentionDays < taskCode.minRetentionDays()
                    || retentionDays > taskCode.maxRetentionDays()) {
                throw validationFailure();
            }
        } else if (retentionDays != null) {
            throw validationFailure();
        }
        Integer uploadGrace = request.uploadPendingGraceMinutes();
        if (taskCode.uploadPendingGraceSupported()) {
            if (uploadGrace == null
                    || uploadGrace < MIN_UPLOAD_GRACE_MINUTES
                    || uploadGrace > MAX_UPLOAD_GRACE_MINUTES) {
                throw validationFailure();
            }
        } else if (uploadGrace != null) {
            throw validationFailure();
        }
        Boolean retainReviews = request.retainReviews();
        if (taskCode.retainReviewsSupported()) {
            if (retainReviews == null) {
                throw validationFailure();
            }
        } else if (retainReviews != null) {
            throw validationFailure();
        }
        String cron = normalizeCron(request.cronExpression(), persisted.zoneId());
        return new NormalizedUpdate(
                request.enabled(),
                retentionDays,
                request.batchSize(),
                cron,
                persisted.zoneId(),
                request.batchIntervalSeconds(),
                uploadGrace,
                retainReviews,
                persisted
        );
    }

    private boolean configChanged(
            NormalizedUpdate update,
            DataCleanupTaskSetting persisted
    ) {
        return update.enabled() != persisted.enabled()
                || !Objects.equals(update.retentionDays(), persisted.retentionDays())
                || update.batchSize() != persisted.batchSize()
                || !Objects.equals(update.cronExpression(), persisted.cronExpression())
                || !Objects.equals(update.zoneId(), persisted.zoneId())
                || update.batchIntervalSeconds() != persisted.batchIntervalSeconds()
                || !Objects.equals(
                        update.uploadPendingGraceMinutes(),
                        persisted.uploadPendingGraceMinutes())
                || !Objects.equals(update.retainReviews(), persisted.retainReviews());
    }

    private LocalDateTime nextRunAtAfterUpdate(
            NormalizedUpdate update,
            DataCleanupTaskSetting persisted,
            LocalDateTime now
    ) {
        if (!update.enabled()) {
            return null;
        }
        if (!persisted.enabled()
                || persisted.nextRunAt() == null
                || !Objects.equals(update.cronExpression(), persisted.cronExpression())
                || !Objects.equals(update.zoneId(), persisted.zoneId())) {
            return nextRunAt(update.cronExpression(), update.zoneId(), now);
        }
        return persisted.nextRunAt();
    }

    private String normalizeCron(String value, String zoneId) {
        if (value == null || value.isBlank()) {
            throw validationFailure();
        }
        String cron = value.trim().replaceAll("\\s+", " ");
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZonedDateTime base = ZonedDateTime.now(ZoneId.of(zoneId)).withNano(0);
            ZonedDateTime first = expression.next(base);
            ZonedDateTime second = first == null ? null : expression.next(first);
            if (first == null || second == null
                    || Duration.between(first.toInstant(), second.toInstant()).getSeconds() < 60) {
                throw validationFailure();
            }
        } catch (IllegalArgumentException ex) {
            throw validationFailure();
        }
        return cron;
    }

    private List<DataCleanupTaskSetting> allSettings() {
        return jdbcClient.sql(settingSelect())
                .query(this::mapSetting)
                .list()
                .stream()
                .sorted(Comparator.comparingInt(setting -> setting.taskCode().ordinal()))
                .toList();
    }

    private String settingSelect() {
        return """
                select task_code, enabled, retention_days, batch_size,
                       cron_expression, zone_id, batch_interval_seconds,
                       upload_pending_grace_minutes, retain_reviews,
                       config_revision, next_run_at,
                       run_sequence,
                       last_started_at, last_completed_at, last_status,
                       last_processed_count, last_error, updated_at
                from data_cleanup_task_setting
                """;
    }

    private DataCleanupTaskSetting mapSetting(ResultSet rs, int rowNum) throws SQLException {
        return new DataCleanupTaskSetting(
                DataCleanupTaskCode.valueOf(rs.getString("task_code").toUpperCase(Locale.ROOT)),
                rs.getBoolean("enabled"),
                rs.getObject("retention_days", Integer.class),
                rs.getInt("batch_size"),
                rs.getString("cron_expression"),
                rs.getString("zone_id"),
                rs.getInt("batch_interval_seconds"),
                rs.getObject("upload_pending_grace_minutes", Integer.class),
                rs.getObject("retain_reviews", Boolean.class),
                rs.getLong("config_revision"),
                rs.getLong("run_sequence"),
                rs.getObject("next_run_at", LocalDateTime.class),
                rs.getObject("last_started_at", LocalDateTime.class),
                rs.getObject("last_completed_at", LocalDateTime.class),
                rs.getString("last_status"),
                rs.getInt("last_processed_count"),
                rs.getString("last_error"),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private DataCleanupTaskResponse toResponse(DataCleanupTaskSetting setting) {
        DataCleanupTaskCode taskCode = setting.taskCode();
        return new DataCleanupTaskResponse(
                taskCode,
                taskCode.title(),
                taskCode.description(),
                setting.enabled(),
                setting.retentionDays(),
                taskCode.minRetentionDays(),
                taskCode.maxRetentionDays(),
                setting.batchSize(),
                taskCode.maxBatchSize(),
                setting.cronExpression(),
                setting.zoneId(),
                setting.batchIntervalSeconds(),
                setting.uploadPendingGraceMinutes(),
                setting.retainReviews(),
                setting.nextRunAt(),
                setting.lastStartedAt(),
                setting.lastCompletedAt(),
                setting.lastStatus(),
                setting.lastProcessedCount(),
                setting.lastError(),
                setting.updatedAt()
        );
    }

    private void requireCompleteTaskSet(List<DataCleanupTaskCode> taskCodes) {
        if (taskCodes == null
                || taskCodes.size() != DataCleanupTaskCode.values().length
                || !EnumSet.copyOf(taskCodes).equals(EnumSet.allOf(DataCleanupTaskCode.class))) {
            throw validationFailure();
        }
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private String cleanError(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() <= MAX_ERROR_LENGTH ? clean : clean.substring(0, MAX_ERROR_LENGTH);
    }

    private void recordOutcomeWithoutRescheduling(
            DataCleanupClaim claim,
            String status,
            int processedCount,
            String error
    ) {
        jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set lease_token = null,
                            lease_until = null,
                            last_completed_at = current_timestamp,
                            last_status = :status,
                            last_processed_count = :processedCount,
                            last_error = :lastError,
                            updated_at = current_timestamp
                        where task_code = :taskCode
                          and lease_token = :leaseToken
                        """)
                .param("status", status)
                .param("processedCount", Math.max(0, processedCount))
                .param("lastError", error)
                .param("taskCode", claim.setting().taskCode().name())
                .param("leaseToken", claim.leaseToken())
                .update();
    }

    private static BusinessException validationFailure() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    public record DataCleanupClaim(
            DataCleanupTaskSetting setting,
            String leaseToken
    ) {
    }

    private record NormalizedUpdate(
            boolean enabled,
            Integer retentionDays,
            int batchSize,
            String cronExpression,
            String zoneId,
            int batchIntervalSeconds,
            Integer uploadPendingGraceMinutes,
            Boolean retainReviews,
            DataCleanupTaskSetting persisted
    ) {

        private NormalizedUpdate withPersisted(DataCleanupTaskSetting current) {
            return new NormalizedUpdate(
                    enabled,
                    retentionDays,
                    batchSize,
                    cronExpression,
                    zoneId,
                    batchIntervalSeconds,
                    uploadPendingGraceMinutes,
                    retainReviews,
                    current
            );
        }
    }
}
