package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.dto.AdminWechatShippingRuntimeUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

@Service
public class WechatShippingRuntimeSettingService {

    private static final Logger log = LoggerFactory.getLogger(
            WechatShippingRuntimeSettingService.class
    );
    private static final long SETTING_ID = 1L;
    private static final String AUTOMATIC_CHANGE_REASON = "管理员调整微信订单同步设置";

    private final JdbcClient jdbcClient;

    public WechatShippingRuntimeSettingService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public RuntimeSetting current() {
        return jdbcClient.sql("""
                        select upload_enabled, delivery_enabled,
                               receipt_reconciliation_enabled, revision,
                               change_reason, updated_by, updated_at
                        from wechat_shipping_runtime_setting
                        where id = :id
                        """)
                .param("id", SETTING_ID)
                .query(this::map)
                .optional()
                .orElseGet(this::databaseSafeDefault);
    }

    public boolean uploadEnabledFailClosed() {
        try {
            return current().uploadEnabled();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping runtime upload check failed; upload was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    public boolean deliveryEnabledFailClosed() {
        try {
            RuntimeSetting setting = current();
            return setting.uploadEnabled() && setting.deliveryEnabled();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping runtime delivery check failed; delivery was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    public boolean receiptReconciliationEnabledFailClosed() {
        try {
            RuntimeSetting setting = current();
            return setting.uploadEnabled() && setting.receiptReconciliationEnabled();
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat shipping runtime receipt check failed; reconciliation was skipped (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    @Transactional
    public RuntimeSetting update(
            AdminWechatShippingRuntimeUpdateRequest request,
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

        long nextVersion = before.version() + 1;
        if (before.persisted()) {
            updateExisting(request.version(), update, updatedBy);
        } else {
            insertFirst(update, updatedBy);
        }
        appendAudit(before, update, nextVersion, updatedBy);
        return current();
    }

    private NormalizedUpdate normalize(AdminWechatShippingRuntimeUpdateRequest request) {
        if (request == null || request.uploadEnabled() == null
                || request.deliveryEnabled() == null
                || request.receiptReconciliationEnabled() == null
                || request.version() == null || request.version() < 0) {
            throw validation();
        }
        boolean uploadEnabled = request.uploadEnabled();
        boolean deliveryEnabled = request.deliveryEnabled();
        boolean receiptEnabled = request.receiptReconciliationEnabled();
        if (!uploadEnabled && (deliveryEnabled || receiptEnabled)) {
            throw validation();
        }
        return new NormalizedUpdate(
                uploadEnabled, deliveryEnabled, receiptEnabled, AUTOMATIC_CHANGE_REASON
        );
    }

    private void updateExisting(
            long expectedVersion,
            NormalizedUpdate update,
            Long updatedBy
    ) {
        int updated = jdbcClient.sql("""
                        update wechat_shipping_runtime_setting
                        set upload_enabled = :uploadEnabled,
                            delivery_enabled = :deliveryEnabled,
                            receipt_reconciliation_enabled = :receiptEnabled,
                            revision = revision + 1,
                            change_reason = :reason,
                            updated_by = :updatedBy,
                            updated_at = current_timestamp
                        where id = :id and revision = :revision
                        """)
                .param("uploadEnabled", update.uploadEnabled())
                .param("deliveryEnabled", update.deliveryEnabled())
                .param("receiptEnabled", update.receiptReconciliationEnabled())
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
                            insert into wechat_shipping_runtime_setting (
                                id, upload_enabled, delivery_enabled,
                                receipt_reconciliation_enabled, revision,
                                change_reason, updated_by, created_at, updated_at
                            ) values (
                                :id, :uploadEnabled, :deliveryEnabled,
                                :receiptEnabled, 1,
                                :reason, :updatedBy, current_timestamp, current_timestamp
                            )
                            """)
                    .param("id", SETTING_ID)
                    .param("uploadEnabled", update.uploadEnabled())
                    .param("deliveryEnabled", update.deliveryEnabled())
                    .param("receiptEnabled", update.receiptReconciliationEnabled())
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
                        insert into wechat_shipping_runtime_audit (
                            revision,
                            upload_enabled_before, delivery_enabled_before,
                            receipt_reconciliation_enabled_before,
                            upload_enabled_after, delivery_enabled_after,
                            receipt_reconciliation_enabled_after,
                            change_reason, operator_id, created_at
                        ) values (
                            :revision,
                            :uploadBefore, :deliveryBefore, :receiptBefore,
                            :uploadAfter, :deliveryAfter, :receiptAfter,
                            :reason, :operatorId, current_timestamp
                        )
                        """)
                .param("revision", revision)
                .param("uploadBefore", before.uploadEnabled())
                .param("deliveryBefore", before.deliveryEnabled())
                .param("receiptBefore", before.receiptReconciliationEnabled())
                .param("uploadAfter", after.uploadEnabled())
                .param("deliveryAfter", after.deliveryEnabled())
                .param("receiptAfter", after.receiptReconciliationEnabled())
                .param("reason", after.reason())
                .param("operatorId", operatorId)
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("WeChat shipping runtime audit was not persisted");
        }
    }

    private RuntimeSetting map(ResultSet rs, int rowNum) throws SQLException {
        return new RuntimeSetting(
                rs.getBoolean("upload_enabled"),
                rs.getBoolean("delivery_enabled"),
                rs.getBoolean("receipt_reconciliation_enabled"),
                true,
                rs.getLong("revision"),
                rs.getString("change_reason"),
                rs.getObject("updated_by", Long.class),
                rs.getObject("updated_at", LocalDateTime.class),
                false, false, false
        );
    }

    private RuntimeSetting databaseSafeDefault() {
        return new RuntimeSetting(
                false, false, false,
                false, 0L, "", null, null,
                false, false, false
        );
    }

    private BusinessException validation() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.WECHAT_SHIPPING_RUNTIME_CONFLICT);
    }

    private record NormalizedUpdate(
            boolean uploadEnabled,
            boolean deliveryEnabled,
            boolean receiptReconciliationEnabled,
            String reason
    ) {
    }

    public record RuntimeSetting(
            boolean uploadEnabled,
            boolean deliveryEnabled,
            boolean receiptReconciliationEnabled,
            boolean persisted,
            long version,
            String reason,
            Long updatedBy,
            LocalDateTime updatedAt,
            boolean defaultUploadEnabled,
            boolean defaultDeliveryEnabled,
            boolean defaultReceiptReconciliationEnabled
    ) {
    }
}
