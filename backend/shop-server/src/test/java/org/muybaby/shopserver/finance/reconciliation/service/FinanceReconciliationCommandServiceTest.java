package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationInvestigateRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationResolveRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationRetryRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class FinanceReconciliationCommandServiceTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FinanceReconciliationReadService readService;

    @Test
    void retryUsesVersionCasResetsAttemptsAndAuditsOperator() {
        long batchId = 9_360_101L;
        deleteBatch(batchId);
        insertBatch(batchId, "FAILED", 3L, 8);
        FinanceReconciliationCommandService service = service();
        try {
            assertThatThrownBy(() -> service.retry(
                    batchId, new AdminReconciliationRetryRequest(2L, "stale"), 81L))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.errorCode())
                                    .isEqualTo(ErrorCode.FINANCE_RECONCILIATION_CONFLICT));

            var response = service.retry(
                    batchId, new AdminReconciliationRetryRequest(3L, "manual retry"), 82L);

            assertThat(response.status()).isEqualTo("PENDING");
            assertThat(response.version()).isEqualTo(4L);
            assertThat(jdbcClient.sql("""
                            select attempt_count from finance_reconciliation_batch where id = :id
                            """)
                    .param("id", batchId)
                    .query(Integer.class)
                    .single()).isZero();
            assertThat(jdbcClient.sql("""
                            select operator_id from finance_reconciliation_resolution_audit
                            where batch_id = :batchId and action = 'RETRY'
                            """)
                    .param("batchId", batchId)
                    .query(Long.class)
                    .single()).isEqualTo(82L);
        } finally {
            deleteBatch(batchId);
        }
    }

    @Test
    void investigateAndResolveUseCasAndPersistResponsibleOperators() {
        long batchId = 9_360_102L;
        long differenceId = 9_360_103L;
        deleteBatch(batchId);
        insertBatch(batchId, "DIFFERENCES", 0L, 1);
        insertDifference(differenceId, batchId);
        FinanceReconciliationCommandService service = service();
        try {
            var investigating = service.investigate(
                    differenceId,
                    new AdminReconciliationInvestigateRequest(0L, "checking evidence"),
                    91L);
            assertThat(investigating.status()).isEqualTo("INVESTIGATING");
            assertThat(investigating.version()).isEqualTo(1L);

            assertThatThrownBy(() -> service.resolve(
                    differenceId,
                    new AdminReconciliationResolveRequest(0L, "MATCHED", "stale"),
                    92L))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.errorCode())
                                    .isEqualTo(ErrorCode.FINANCE_RECONCILIATION_CONFLICT));

            var resolved = service.resolve(
                    differenceId,
                    new AdminReconciliationResolveRequest(1L, "MATCHED", "verified"),
                    93L);
            assertThat(resolved.status()).isEqualTo("RESOLVED");
            assertThat(resolved.resolvedBy()).isEqualTo(93L);
            assertThat(resolved.version()).isEqualTo(2L);
            assertThat(jdbcClient.sql("""
                            select action, operator_id
                            from finance_reconciliation_resolution_audit
                            where difference_id = :differenceId
                            order by id
                            """)
                    .param("differenceId", differenceId)
                    .query((rs, rowNum) -> rs.getString("action")
                            + ":" + rs.getLong("operator_id"))
                    .list()).containsExactly("INVESTIGATE:91", "RESOLVE:93");
        } finally {
            deleteBatch(batchId);
        }
    }

    @Test
    void concurrentDifferenceCommandsSerializeOnBatchBeforeDifferenceWithoutDeadlock()
            throws Exception {
        long batchId = 9_360_104L;
        long differenceId = 9_360_105L;
        deleteBatch(batchId);
        insertBatch(batchId, "DIFFERENCES", 0L, 1);
        insertDifference(differenceId, batchId);
        FinanceReconciliationCommandService service = service();
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> investigate = () -> {
            start.await();
            try {
                service.investigate(
                        differenceId,
                        new AdminReconciliationInvestigateRequest(0L, "concurrent investigate"),
                        101L);
                return "OK";
            } catch (BusinessException ex) {
                return ex.errorCode().name();
            }
        };
        Callable<String> resolve = () -> {
            start.await();
            try {
                service.resolve(
                        differenceId,
                        new AdminReconciliationResolveRequest(0L, "MATCHED", "concurrent resolve"),
                        102L);
                return "OK";
            } catch (BusinessException ex) {
                return ex.errorCode().name();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(investigate);
            var second = executor.submit(resolve);
            start.countDown();
            assertThat(List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            "OK", ErrorCode.FINANCE_RECONCILIATION_CONFLICT.name());
        } finally {
            deleteBatch(batchId);
        }
    }

    private FinanceReconciliationCommandService service() {
        FinanceReconciliationProperties properties = new FinanceReconciliationProperties(
                null, null, null, null, null,
                0, 0, null, 0, 0, 0, 0);
        FinanceReconciliationRuntimeSettingService runtime = mock(
                FinanceReconciliationRuntimeSettingService.class
        );
        when(runtime.workerEnabledFailClosed()).thenReturn(true);
        Clock clock = Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);
        return new FinanceReconciliationCommandService(
                jdbcClient,
                new TransactionTemplate(transactionManager),
                mock(ReconciliationCredentialCatalog.class),
                readService,
                properties,
                runtime,
                clock
        );
    }

    private void insertBatch(long id, String status, long version, int attemptCount) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (id, mch_id, bill_date, bill_type, status, phase,
                             attempt_count, difference_count, open_difference_count,
                             version, requested_at, created_at, updated_at)
                        values
                            (:id, :mchId, date '2026-08-08', 'TRADE_ALL', :status, 'COMPLETE',
                             :attemptCount, 1, 1, :version, :now, :now, :now)
                        """)
                .param("id", id)
                .param("mchId", "mch-command-" + id)
                .param("status", status)
                .param("attemptCount", attemptCount)
                .param("version", version)
                .param("now", now)
                .update();
    }

    private void insertDifference(long id, long batchId) {
        jdbcClient.sql("""
                        insert into finance_reconciliation_difference
                            (id, batch_id, diff_key, difference_type, severity, status,
                             provider_evidence, local_evidence)
                        values
                            (:id, :batchId, :diffKey, 'STATUS_MISMATCH', 'CRITICAL', 'OPEN',
                             '{}', '{}')
                        """)
                .param("id", id)
                .param("batchId", batchId)
                .param("diffKey", "d".repeat(64))
                .update();
    }

    private void deleteBatch(long batchId) {
        jdbcClient.sql("""
                        delete from finance_reconciliation_resolution_audit
                        where batch_id = :batchId
                        """)
                .param("batchId", batchId)
                .update();
        jdbcClient.sql("delete from finance_reconciliation_difference where batch_id = :batchId")
                .param("batchId", batchId)
                .update();
        jdbcClient.sql("delete from wechat_trade_bill_entry where batch_id = :batchId")
                .param("batchId", batchId)
                .update();
        jdbcClient.sql("delete from finance_reconciliation_batch where id = :batchId")
                .param("batchId", batchId)
                .update();
    }
}
