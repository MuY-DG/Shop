package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.finance.reconciliation.dto.AdminFinanceReconciliationRuntimeResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminFinanceReconciliationRuntimeUpdateRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceReconciliationAdminRuntimeService {

    private final JdbcClient jdbcClient;
    private final FinanceReconciliationRuntimeSettingService runtimeSettingService;

    public FinanceReconciliationAdminRuntimeService(
            JdbcClient jdbcClient,
            FinanceReconciliationRuntimeSettingService runtimeSettingService
    ) {
        this.jdbcClient = jdbcClient;
        this.runtimeSettingService = runtimeSettingService;
    }

    @Transactional(readOnly = true)
    public AdminFinanceReconciliationRuntimeResponse status() {
        FinanceReconciliationRuntimeSettingService.RuntimeSetting runtime =
                runtimeSettingService.current();
        FinanceReconciliationRuntimeSettingService.Readiness readiness =
                runtimeSettingService.readiness();
        boolean workerReady = runtime.workerEnabled() && readiness.workerReady();
        return new AdminFinanceReconciliationRuntimeResponse(
                runtime.workerEnabled(), runtime.dailyEnabled(), runtime.persisted(),
                runtime.version(), runtime.defaultWorkerEnabled(), runtime.defaultDailyEnabled(),
                runtime.reason(), runtime.updatedBy(), runtime.updatedAt(),
                readiness.paymentCredentialsReady(), readiness.privateStorageReady(),
                workerReady, workerReady && runtime.dailyEnabled(),
                batchCount("PENDING"), batchCount("RUNNING"), batchCount("RETRY_WAIT"),
                batchCount("FAILED"), openDifferenceCount()
        );
    }

    @Transactional
    public AdminFinanceReconciliationRuntimeResponse update(
            AdminFinanceReconciliationRuntimeUpdateRequest request,
            Long operatorId
    ) {
        runtimeSettingService.update(request, operatorId);
        return status();
    }

    private long batchCount(String status) {
        return jdbcClient.sql("""
                        select count(*) from finance_reconciliation_batch where status = :status
                        """)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private long openDifferenceCount() {
        return jdbcClient.sql("""
                        select count(*) from finance_reconciliation_difference
                        where status in ('OPEN', 'INVESTIGATING')
                        """)
                .query(Long.class)
                .single();
    }
}
