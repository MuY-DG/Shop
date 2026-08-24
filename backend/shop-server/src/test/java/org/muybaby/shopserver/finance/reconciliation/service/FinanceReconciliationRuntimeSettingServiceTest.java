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
        assertThat(defaults.persisted()).isTrue();
        assertThat(defaults.workerEnabled()).isFalse();
        assertThat(defaults.dailyEnabled()).isFalse();
        assertThat(defaults.version()).isOne();

        FinanceReconciliationRuntimeSettingService.RuntimeSetting persisted = service.update(
                request(false, false, 1L, "keep disabled during rollout"),
                81L
        );

        assertThat(persisted.persisted()).isTrue();
        assertThat(persisted.version()).isEqualTo(2L);
        assertThat(persisted.updatedBy()).isEqualTo(81L);
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_runtime_audit
                        where revision = 2 and worker_enabled_before = false
                          and worker_enabled_after = false and operator_id = 81
                        """)
                .query(Long.class)
                .single()).isOne();

        assertThatThrownBy(() -> service.update(
                request(false, false, 1L, "stale update"),
                82L
        )).isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.errorCode())
                        .isEqualTo(ErrorCode.FINANCE_RECONCILIATION_RUNTIME_CONFLICT));
    }

    @Test
    void dailyCannotBypassWorkerAndEmergencyShutdownDoesNotRequireReadiness() {
        assertThatThrownBy(() -> service.update(
                request(false, true, 1L, "invalid daily enable"),
                91L
        )).isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        jdbcClient.sql("""
                        update finance_reconciliation_runtime_setting
                        set worker_enabled = true,
                            daily_enabled = true,
                            revision = 2,
                            change_reason = 'seed enabled state',
                            updated_by = 1,
                            updated_at = current_timestamp
                        where id = 1
                        """)
                .update();

        FinanceReconciliationRuntimeSettingService.RuntimeSetting disabled = service.update(
                request(false, false, 2L, "emergency shutdown"),
                92L
        );

        assertThat(disabled.workerEnabled()).isFalse();
        assertThat(disabled.dailyEnabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(3L);
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
