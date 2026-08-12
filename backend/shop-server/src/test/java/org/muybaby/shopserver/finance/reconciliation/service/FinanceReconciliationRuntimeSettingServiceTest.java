package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminFinanceReconciliationRuntimeUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinanceReconciliationRuntimeSettingServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FinanceReconciliationRuntimeSettingService service;

    @Test
    void databaseOverrideIsVersionedAuditedAndRejectsStaleUpdates() {
        FinanceReconciliationRuntimeSettingService.RuntimeSetting defaults = service.current();
        assertThat(defaults.persisted()).isFalse();
        assertThat(defaults.workerEnabled()).isFalse();
        assertThat(defaults.dailyEnabled()).isFalse();
        assertThat(defaults.version()).isZero();

        FinanceReconciliationRuntimeSettingService.RuntimeSetting persisted = service.update(
                request(false, false, 0L, "keep disabled during rollout"),
                81L
        );

        assertThat(persisted.persisted()).isTrue();
        assertThat(persisted.version()).isOne();
        assertThat(persisted.updatedBy()).isEqualTo(81L);
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_runtime_audit
                        where revision = 1 and worker_enabled_before = false
                          and worker_enabled_after = false and operator_id = 81
                        """)
                .query(Long.class)
                .single()).isOne();

        assertThatThrownBy(() -> service.update(
                request(false, false, 0L, "stale update"),
                82L
        )).isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.errorCode())
                        .isEqualTo(ErrorCode.FINANCE_RECONCILIATION_RUNTIME_CONFLICT));
    }

    @Test
    void dailyCannotBypassWorkerAndEmergencyShutdownDoesNotRequireReadiness() {
        assertThatThrownBy(() -> service.update(
                request(false, true, 0L, "invalid daily enable"),
                91L
        )).isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        jdbcClient.sql("""
                        insert into finance_reconciliation_runtime_setting
                            (id, worker_enabled, daily_enabled, revision,
                             change_reason, updated_by, created_at, updated_at)
                        values
                            (1, true, true, 1, 'seed enabled state', 1,
                             current_timestamp, current_timestamp)
                        """)
                .update();

        FinanceReconciliationRuntimeSettingService.RuntimeSetting disabled = service.update(
                request(false, false, 1L, "emergency shutdown"),
                92L
        );

        assertThat(disabled.workerEnabled()).isFalse();
        assertThat(disabled.dailyEnabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(2L);
    }

    private AdminFinanceReconciliationRuntimeUpdateRequest request(
            boolean workerEnabled,
            boolean dailyEnabled,
            long version,
            String reason
    ) {
        return new AdminFinanceReconciliationRuntimeUpdateRequest(
                workerEnabled, dailyEnabled, version, reason
        );
    }
}
