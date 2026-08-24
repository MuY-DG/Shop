package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminFinanceReconciliationRuntimeUpdateRequest;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class FinanceReconciliationRuntimeSettingService {

    private static final Logger log = LoggerFactory.getLogger(
            FinanceReconciliationRuntimeSettingService.class
    );
    private static final long SETTING_ID = 1L;

    private final JdbcClient jdbcClient;
    private final ReconciliationCredentialCatalog credentialCatalog;
    private final StorageRuntimeConfigService storageConfigService;
    private final Clock clock;

    public FinanceReconciliationRuntimeSettingService(
            JdbcClient jdbcClient,
            ReconciliationCredentialCatalog credentialCatalog,
            StorageRuntimeConfigService storageConfigService,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.credentialCatalog = credentialCatalog;
        this.storageConfigService = storageConfigService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RuntimeSetting current() {
        return jdbcClient.sql("""
                        select worker_enabled, daily_enabled, revision, change_reason,
                               updated_by, updated_at
                        from finance_reconciliation_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::map)
                .optional()
                .orElseGet(this::databaseSafeDefault);
    }

    public boolean workerEnabledFailClosed() {
        try {
            return current().workerEnabled();
        } catch (RuntimeException ex) {
            log.warn(
                    "Finance reconciliation runtime worker check failed; work was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    public boolean dailyEnabledFailClosed() {
        try {
            RuntimeSetting setting = current();
            return setting.workerEnabled() && setting.dailyEnabled();
        } catch (RuntimeException ex) {
            log.warn(
                    "Finance reconciliation runtime daily check failed; scheduling was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    public Readiness readiness() {
        boolean paymentReady = paymentCredentialsReady();
        boolean storageReady = privateStorageReady();
        return new Readiness(paymentReady, storageReady);
    }

    @Transactional
    public RuntimeSetting update(
            AdminFinanceReconciliationRuntimeUpdateRequest request,
            Long updatedBy
    ) {
        if (updatedBy == null || updatedBy <= 0) {
            throw validation();
        }
        NormalizedUpdate update = normalize(request);
        RuntimeSetting before = current();
        if (before.version() != request.version()) {
            throw conflict();
        }

        boolean enablingWorker = !before.workerEnabled() && update.workerEnabled();
        boolean enablingDaily = !before.dailyEnabled() && update.dailyEnabled();
        if (enablingWorker || enablingDaily) {
            Readiness readiness = readiness();
            if (!readiness.paymentCredentialsReady() || !readiness.privateStorageReady()) {
                throw validation();
            }
        }
        if (enablingDaily && !before.workerEnabled()) {
            throw validation();
        }

        long nextVersion = before.version() + 1;
        if (before.persisted()) {
            updateExisting(request.version(), update, updatedBy);
        } else {
            insertFirst(update, updatedBy);
        }
        appendAudit(before, update, nextVersion, updatedBy);
        return current();
    }

    private boolean paymentCredentialsReady() {
        try {
            LocalDate previousDay = LocalDate.now(clock.withZone(TimePolicy.BUSINESS_ZONE))
                    .minusDays(1);
            return !credentialCatalog.available(previousDay).isEmpty();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean privateStorageReady() {
        try {
            storageConfigService.effective();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private NormalizedUpdate normalize(AdminFinanceReconciliationRuntimeUpdateRequest request) {
        if (request == null || request.workerEnabled() == null
                || request.dailyEnabled() == null || request.version() == null
                || request.version() < 0 || !StringUtils.hasText(request.reason())) {
            throw validation();
        }
        String reason = request.reason().trim();
        if (reason.length() < 2 || reason.length() > 200 || hasControlCharacter(reason)) {
            throw validation();
        }
        boolean workerEnabled = request.workerEnabled();
        boolean dailyEnabled = request.dailyEnabled();
        if (dailyEnabled && !workerEnabled) {
            throw validation();
        }
        return new NormalizedUpdate(workerEnabled, dailyEnabled, reason);
    }

    private void updateExisting(
            long expectedVersion,
            NormalizedUpdate update,
            Long updatedBy
    ) {
        int updated = jdbcClient.sql("""
                        update finance_reconciliation_runtime_setting
                        set worker_enabled = :workerEnabled,
                            daily_enabled = :dailyEnabled,
                            revision = revision + 1,
                            change_reason = :reason,
                            updated_by = :updatedBy,
                            updated_at = current_timestamp
                        where id = :id and revision = :revision
                        """)
                .param("workerEnabled", update.workerEnabled())
                .param("dailyEnabled", update.dailyEnabled())
                .param("reason", update.reason())
                .param("updatedBy", updatedBy)
                .param("id", SETTING_ID)
                .param("revision", expectedVersion)
                .update();
        if (updated != 1) {
            throw conflict();
        }
    }

    private void insertFirst(NormalizedUpdate update, Long updatedBy) {
        try {
            int inserted = jdbcClient.sql("""
                            insert into finance_reconciliation_runtime_setting (
                                id, worker_enabled, daily_enabled, revision,
                                change_reason, updated_by, created_at, updated_at
                            ) values (
                                :id, :workerEnabled, :dailyEnabled, 1,
                                :reason, :updatedBy, current_timestamp, current_timestamp
                            )
                            """)
                    .param("id", SETTING_ID)
                    .param("workerEnabled", update.workerEnabled())
                    .param("dailyEnabled", update.dailyEnabled())
                    .param("reason", update.reason())
                    .param("updatedBy", updatedBy)
                    .update();
            if (inserted != 1) {
                throw conflict();
            }
        } catch (DuplicateKeyException ex) {
            throw conflict();
        }
    }

    private void appendAudit(
            RuntimeSetting before,
            NormalizedUpdate after,
            long revision,
            Long operatorId
    ) {
        int inserted = jdbcClient.sql("""
                        insert into finance_reconciliation_runtime_audit (
                            revision, worker_enabled_before, daily_enabled_before,
                            worker_enabled_after, daily_enabled_after,
                            change_reason, operator_id, created_at
                        ) values (
                            :revision, :workerBefore, :dailyBefore,
                            :workerAfter, :dailyAfter,
                            :reason, :operatorId, current_timestamp
                        )
                        """)
                .param("revision", revision)
                .param("workerBefore", before.workerEnabled())
                .param("dailyBefore", before.dailyEnabled())
                .param("workerAfter", after.workerEnabled())
                .param("dailyAfter", after.dailyEnabled())
                .param("reason", after.reason())
                .param("operatorId", operatorId)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("Finance reconciliation runtime audit was not persisted");
        }
    }

    private RuntimeSetting map(ResultSet rs, int rowNum) throws SQLException {
        return new RuntimeSetting(
                rs.getBoolean("worker_enabled"),
                rs.getBoolean("daily_enabled"),
                true,
                rs.getLong("revision"),
                rs.getString("change_reason"),
                rs.getObject("updated_by", Long.class),
                rs.getObject("updated_at", LocalDateTime.class),
                false, false
        );
    }

    private RuntimeSetting databaseSafeDefault() {
        return new RuntimeSetting(
                false, false, false, 0L, "", null, null,
                false, false
        );
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || Character.getType(codePoint) == Character.FORMAT
        );
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.FINANCE_RECONCILIATION_RUNTIME_CONFLICT);
    }

    private record NormalizedUpdate(
            boolean workerEnabled,
            boolean dailyEnabled,
            String reason
    ) {
    }

    public record Readiness(
            boolean paymentCredentialsReady,
            boolean privateStorageReady
    ) {
        public boolean workerReady() {
            return paymentCredentialsReady && privateStorageReady;
        }
    }

    public record RuntimeSetting(
            boolean workerEnabled,
            boolean dailyEnabled,
            boolean persisted,
            long version,
            String reason,
            Long updatedBy,
            LocalDateTime updatedAt,
            boolean defaultWorkerEnabled,
            boolean defaultDailyEnabled
    ) {
    }
}
