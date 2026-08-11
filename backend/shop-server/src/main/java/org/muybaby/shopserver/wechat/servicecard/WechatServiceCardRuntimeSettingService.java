package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

@Service
public class WechatServiceCardRuntimeSettingService {

    private static final Logger log = LoggerFactory.getLogger(
            WechatServiceCardRuntimeSettingService.class
    );
    private static final long SETTING_ID = 1L;

    private final JdbcClient jdbcClient;
    private final WechatServiceCardProperties properties;
    private final WechatMiniProgramProperties miniProgramProperties;

    public WechatServiceCardRuntimeSettingService(
            JdbcClient jdbcClient,
            WechatServiceCardProperties properties,
            WechatMiniProgramProperties miniProgramProperties
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.miniProgramProperties = miniProgramProperties;
    }

    @Transactional(readOnly = true)
    public RuntimeSetting current() {
        return jdbcClient.sql("""
                        select capture_enabled, worker_enabled, revision, change_reason,
                               updated_by, updated_at
                        from wechat_service_card_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::map)
                .optional()
                .orElseGet(this::environmentDefault);
    }

    /**
     * Order lifecycle callers must never fail a transaction because the optional service-card
     * runtime row cannot be read. Capture is skipped and the next order fact or repair scan can
     * recover it.
     */
    public boolean captureEnabledFailSoft() {
        try {
            return current().captureEnabled();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat 2001 runtime capture check failed; transaction capture was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    /** Provider work is always fail-closed and re-reads the database-backed switch. */
    public boolean workerReadyFailClosed() {
        try {
            RuntimeSetting setting = current();
            return setting.captureEnabled()
                    && setting.workerEnabled()
                    && staticCaptureReady()
                    && staticWorkerReady();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat 2001 runtime worker check failed; provider work was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    @Transactional
    public RuntimeSetting update(
            AdminWechatServiceCardRuntimeUpdateRequest request,
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

        // Readiness gates only apply to transitions that turn work on. Emergency shutdowns
        // must remain possible even while an environment dependency is broken.
        if (!before.captureEnabled() && update.captureEnabled() && !staticCaptureReady()) {
            throw validation();
        }
        if (!before.workerEnabled() && update.workerEnabled()) {
            // A false/false deployment must first persist a capture-only revision so operators
            // can inspect the repair window before any provider work is allowed.
            if (!before.captureEnabled()) {
                throw validation();
            }
            if (!staticWorkerReady()) {
                throw validation();
            }
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

    public boolean staticCaptureReady() {
        return properties.imageConfigurationReady();
    }

    public boolean staticWorkerReady() {
        return staticCaptureReady()
                && properties.templateConfigurationReady()
                && StringUtils.hasText(miniProgramProperties.appId())
                && StringUtils.hasText(miniProgramProperties.appSecret())
                && callbackReady();
    }

    public boolean callbackReady() {
        return properties.callback().secureReady()
                && StringUtils.hasText(miniProgramProperties.appId());
    }

    private NormalizedUpdate normalize(AdminWechatServiceCardRuntimeUpdateRequest request) {
        if (request == null || request.captureEnabled() == null
                || request.workerEnabled() == null || request.version() == null
                || request.version() < 0 || !StringUtils.hasText(request.reason())) {
            throw validation();
        }
        String reason = request.reason().trim();
        if (reason.length() < 2 || reason.length() > 200 || hasControlCharacter(reason)) {
            throw validation();
        }
        boolean captureEnabled = request.captureEnabled();
        boolean workerEnabled = request.workerEnabled();
        if (workerEnabled && !captureEnabled) {
            throw validation();
        }
        return new NormalizedUpdate(captureEnabled, workerEnabled, reason);
    }

    private void updateExisting(
            long expectedVersion,
            NormalizedUpdate update,
            Long updatedBy
    ) {
        int updated = jdbcClient.sql("""
                        update wechat_service_card_runtime_setting
                        set capture_enabled = :captureEnabled,
                            worker_enabled = :workerEnabled,
                            revision = revision + 1,
                            change_reason = :reason,
                            updated_by = :updatedBy,
                            updated_at = current_timestamp
                        where id = :id and revision = :revision
                        """)
                .param("captureEnabled", update.captureEnabled())
                .param("workerEnabled", update.workerEnabled())
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
                            insert into wechat_service_card_runtime_setting (
                                id, capture_enabled, worker_enabled, revision,
                                change_reason, updated_by, created_at, updated_at
                            ) values (
                                :id, :captureEnabled, :workerEnabled, 1,
                                :reason, :updatedBy, current_timestamp, current_timestamp
                            )
                            """)
                    .param("id", SETTING_ID)
                    .param("captureEnabled", update.captureEnabled())
                    .param("workerEnabled", update.workerEnabled())
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
                        insert into wechat_service_card_runtime_audit (
                            revision, capture_enabled_before, worker_enabled_before,
                            capture_enabled_after, worker_enabled_after,
                            change_reason, operator_id, created_at
                        ) values (
                            :revision, :captureBefore, :workerBefore,
                            :captureAfter, :workerAfter,
                            :reason, :operatorId, current_timestamp
                        )
                        """)
                .param("revision", revision)
                .param("captureBefore", before.captureEnabled())
                .param("workerBefore", before.workerEnabled())
                .param("captureAfter", after.captureEnabled())
                .param("workerAfter", after.workerEnabled())
                .param("reason", after.reason())
                .param("operatorId", operatorId)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("WeChat service-card runtime audit was not persisted");
        }
    }

    private RuntimeSetting map(ResultSet rs, int rowNum) throws SQLException {
        return new RuntimeSetting(
                rs.getBoolean("capture_enabled"),
                rs.getBoolean("worker_enabled"),
                true,
                rs.getLong("revision"),
                rs.getString("change_reason"),
                rs.getObject("updated_by", Long.class),
                rs.getObject("updated_at", LocalDateTime.class),
                properties.enabled(),
                properties.workerEnabled()
        );
    }

    private RuntimeSetting environmentDefault() {
        boolean captureEnabled = properties.enabled();
        boolean workerEnabled = captureEnabled && properties.workerEnabled();
        return new RuntimeSetting(
                captureEnabled, workerEnabled, false, 0L, "", null, null,
                properties.enabled(), properties.workerEnabled()
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
        return new BusinessException(ErrorCode.WECHAT_SERVICE_CARD_RUNTIME_CONFLICT);
    }

    private record NormalizedUpdate(
            boolean captureEnabled,
            boolean workerEnabled,
            String reason
    ) {
    }

    public record RuntimeSetting(
            boolean captureEnabled,
            boolean workerEnabled,
            boolean persisted,
            long version,
            String reason,
            Long updatedBy,
            LocalDateTime updatedAt,
            boolean defaultCaptureEnabled,
            boolean defaultWorkerEnabled
    ) {
    }
}
