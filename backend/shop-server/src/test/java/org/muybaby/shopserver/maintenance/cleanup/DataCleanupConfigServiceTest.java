package org.muybaby.shopserver.maintenance.cleanup;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigUpdateRequest;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupTaskResponse;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupTaskUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DataCleanupConfigServiceTest {

    @Autowired
    private DataCleanupConfigService configService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void updatesAllTasksAtomicallyAndNormalizesCron() {
        DataCleanupConfigResponse current = configService.current();
        List<DataCleanupTaskUpdateRequest> updates = updatesFrom(current);
        int systemLogIndex = indexOf(updates, DataCleanupTaskCode.ADMIN_SYSTEM_LOG);
        DataCleanupTaskUpdateRequest systemLog = updates.get(systemLogIndex);
        updates.set(systemLogIndex, new DataCleanupTaskUpdateRequest(
                systemLog.taskCode(),
                true,
                730,
                37,
                "  0   5  2  * * *  ",
                120,
                null,
                null
        ));

        DataCleanupConfigResponse updated = configService.update(
                new DataCleanupConfigUpdateRequest(current.revision(), updates),
                42L
        );

        DataCleanupTaskResponse persisted = task(updated, DataCleanupTaskCode.ADMIN_SYSTEM_LOG);
        assertThat(updated.revision()).isEqualTo(current.revision() + 1);
        assertThat(persisted.retentionDays()).isEqualTo(730);
        assertThat(persisted.batchSize()).isEqualTo(37);
        assertThat(persisted.cronExpression()).isEqualTo("0 5 2 * * *");
        assertThat(persisted.batchIntervalSeconds()).isEqualTo(120);
        assertThat(persisted.nextRunAt()).isNotNull();
        assertThat(task(updated, DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE).nextRunAt())
                .isNull();
        assertThat(jdbcClient.sql("select updated_by from data_cleanup_config where id = 1")
                .query(Long.class)
                .single()).isEqualTo(42L);
    }

    @Test
    void rejectsTaskSpecificLimitsWithoutPersistingAnything() {
        DataCleanupConfigResponse current = configService.current();
        List<DataCleanupTaskUpdateRequest> updates = updatesFrom(current);
        int customerServiceIndex = indexOf(updates, DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE);
        DataCleanupTaskUpdateRequest customerService = updates.get(customerServiceIndex);
        updates.set(customerServiceIndex, new DataCleanupTaskUpdateRequest(
                customerService.taskCode(),
                customerService.enabled(),
                customerService.retentionDays(),
                10_001,
                customerService.cronExpression(),
                customerService.batchIntervalSeconds(),
                null,
                null
        ));

        assertThatThrownBy(() -> configService.update(
                new DataCleanupConfigUpdateRequest(current.revision(), updates),
                42L
        )).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(configService.current().revision()).isEqualTo(current.revision());
        assertThat(task(configService.current(), DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE)
                .batchSize()).isEqualTo(1_000);
    }

    @Test
    void updatesOrderReviewRetentionAndRejectsItForOtherTasks() {
        DataCleanupConfigResponse current = configService.current();
        List<DataCleanupTaskUpdateRequest> updates = updatesFrom(current);
        int orderIndex = indexOf(updates, DataCleanupTaskCode.ORDER_AGGREGATE);
        DataCleanupTaskUpdateRequest order = updates.get(orderIndex);
        updates.set(orderIndex, new DataCleanupTaskUpdateRequest(
                order.taskCode(),
                order.enabled(),
                order.retentionDays(),
                order.batchSize(),
                order.cronExpression(),
                order.batchIntervalSeconds(),
                null,
                false
        ));

        DataCleanupConfigResponse updated = configService.update(
                new DataCleanupConfigUpdateRequest(current.revision(), updates),
                42L
        );

        assertThat(task(updated, DataCleanupTaskCode.ORDER_AGGREGATE).retainReviews())
                .isFalse();

        List<DataCleanupTaskUpdateRequest> invalid = updatesFrom(updated);
        int analyticsIndex = indexOf(invalid, DataCleanupTaskCode.ANALYTICS_EVENT);
        DataCleanupTaskUpdateRequest analytics = invalid.get(analyticsIndex);
        invalid.set(analyticsIndex, new DataCleanupTaskUpdateRequest(
                analytics.taskCode(),
                analytics.enabled(),
                analytics.retentionDays(),
                analytics.batchSize(),
                analytics.cronExpression(),
                analytics.batchIntervalSeconds(),
                analytics.uploadPendingGraceMinutes(),
                true
        ));

        assertThatThrownBy(() -> configService.update(
                new DataCleanupConfigUpdateRequest(updated.revision(), invalid),
                42L
        )).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsStaleRevision() {
        DataCleanupConfigResponse current = configService.current();

        assertThatThrownBy(() -> configService.update(
                new DataCleanupConfigUpdateRequest(current.revision() + 1, updatesFrom(current)),
                42L
        )).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.errorCode())
                        .isEqualTo(ErrorCode.DATA_CLEANUP_CONFIG_CONFLICT));
    }

    @Test
    void initializesSchedulesAndClaimsAWhenDueTaskOnlyOnce() {
        configService.initializeMissingSchedules();
        DataCleanupTaskSetting initialized = configService.require(DataCleanupTaskCode.ANALYTICS_EVENT);
        assertThat(initialized.nextRunAt()).isNotNull();
        assertThat(configService.require(DataCleanupTaskCode.CUSTOMER_SERVICE_MESSAGE).nextRunAt())
                .isNull();

        LocalDateTime dueAt = databaseNow().minusSeconds(1);
        jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set next_run_at = :dueAt
                        where task_code = 'ANALYTICS_EVENT'
                        """)
                .param("dueAt", dueAt)
                .update();

        DataCleanupConfigService.DataCleanupClaim claim = configService
                .claim(DataCleanupTaskCode.ANALYTICS_EVENT)
                .orElseThrow();
        assertThat(configService.claim(DataCleanupTaskCode.ANALYTICS_EVENT)).isEmpty();

        configService.complete(claim, claim.setting().batchSize());

        DataCleanupTaskSetting completed = configService.require(DataCleanupTaskCode.ANALYTICS_EVENT);
        assertThat(completed.lastStatus()).isEqualTo("SUCCESS");
        assertThat(completed.lastProcessedCount()).isEqualTo(claim.setting().batchSize());
        assertThat(completed.lastCompletedAt()).isNotNull();
        assertThat(completed.nextRunAt()).isAfter(dueAt);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from data_cleanup_task_setting
                        where task_code = 'ANALYTICS_EVENT'
                          and lease_token is null
                          and lease_until is null
                        """)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void computesCronInConfiguredZoneAndStoresUtcSchedule() {
        LocalDateTime next = DataCleanupConfigService.nextRunAt(
                "0 15 3 * * *",
                "Asia/Shanghai",
                LocalDateTime.of(2026, 1, 1, 16, 0)
        );

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 1, 1, 19, 15));
    }

    @Test
    void completionOfAnOldClaimDoesNotOverwriteANewerAdminSchedule() {
        jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set next_run_at = :dueAt
                        where task_code = 'ANALYTICS_EVENT'
                        """)
                .param("dueAt", databaseNow().minusSeconds(1))
                .update();
        DataCleanupConfigService.DataCleanupClaim oldClaim = configService
                .claim(DataCleanupTaskCode.ANALYTICS_EVENT)
                .orElseThrow();

        DataCleanupConfigResponse current = configService.current();
        List<DataCleanupTaskUpdateRequest> updates = updatesFrom(current);
        int analyticsIndex = indexOf(updates, DataCleanupTaskCode.ANALYTICS_EVENT);
        DataCleanupTaskUpdateRequest analytics = updates.get(analyticsIndex);
        updates.set(analyticsIndex, new DataCleanupTaskUpdateRequest(
                analytics.taskCode(),
                analytics.enabled(),
                analytics.retentionDays(),
                analytics.batchSize(),
                "0 0 6 * * *",
                analytics.batchIntervalSeconds(),
                null,
                null
        ));
        configService.update(
                new DataCleanupConfigUpdateRequest(current.revision(), updates),
                42L
        );
        DataCleanupTaskSetting newlyScheduled = configService.require(
                DataCleanupTaskCode.ANALYTICS_EVENT);

        configService.complete(oldClaim, oldClaim.setting().batchSize());

        DataCleanupTaskSetting completed = configService.require(
                DataCleanupTaskCode.ANALYTICS_EVENT);
        assertThat(completed.configRevision()).isEqualTo(current.revision() + 1);
        assertThat(completed.nextRunAt()).isEqualTo(newlyScheduled.nextRunAt());
        assertThat(completed.lastStatus()).isEqualTo("SUCCESS");
        assertThat(completed.lastProcessedCount()).isEqualTo(oldClaim.setting().batchSize());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from data_cleanup_task_setting
                        where task_code = 'ANALYTICS_EVENT'
                          and lease_token is null
                          and lease_until is null
                        """)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void updatingWithoutTaskChangesPreservesExistingCatchUpSchedule() {
        LocalDateTime catchUpAt = databaseNow().plusMinutes(2).withNano(0);
        jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set next_run_at = :catchUpAt
                        where task_code = 'ANALYTICS_EVENT'
                        """)
                .param("catchUpAt", catchUpAt)
                .update();
        DataCleanupConfigResponse current = configService.current();

        DataCleanupConfigResponse updated = configService.update(
                new DataCleanupConfigUpdateRequest(current.revision(), updatesFrom(current)),
                42L
        );

        DataCleanupTaskSetting analytics = configService.require(
                DataCleanupTaskCode.ANALYTICS_EVENT);
        assertThat(updated.revision()).isEqualTo(current.revision() + 1);
        assertThat(analytics.configRevision()).isZero();
        assertThat(analytics.nextRunAt()).isEqualTo(catchUpAt);
    }

    @Test
    void renewsAClaimLeaseAndRetriesFailureAfterTheConfiguredInterval() {
        LocalDateTime dueAt = databaseNow().minusSeconds(1);
        jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set next_run_at = :dueAt,
                            batch_interval_seconds = 120
                        where task_code = 'ANALYTICS_EVENT'
                        """)
                .param("dueAt", dueAt)
                .update();
        DataCleanupConfigService.DataCleanupClaim claim = configService
                .claim(DataCleanupTaskCode.ANALYTICS_EVENT)
                .orElseThrow();
        assertThat(claim.setting().runSequence()).isOne();
        jdbcClient.sql("""
                        update data_cleanup_task_setting
                        set lease_until = :shortLease
                        where task_code = 'ANALYTICS_EVENT'
                          and lease_token = :leaseToken
                        """)
                .param("shortLease", databaseNow().plusMinutes(1))
                .param("leaseToken", claim.leaseToken())
                .update();

        assertThat(configService.renewLease(claim)).isTrue();
        LocalDateTime renewedUntil = jdbcClient.sql("""
                        select lease_until
                        from data_cleanup_task_setting
                        where task_code = 'ANALYTICS_EVENT'
                        """)
                .query(LocalDateTime.class)
                .single();
        assertThat(renewedUntil).isAfter(databaseNow().plusMinutes(20));

        LocalDateTime failureRecordedAt = databaseNow();
        configService.fail(claim, new IllegalStateException("temporary"));
        DataCleanupTaskSetting failed = configService.require(
                DataCleanupTaskCode.ANALYTICS_EVENT);
        assertThat(failed.lastStatus()).isEqualTo("FAILED");
        assertThat(failed.nextRunAt())
                .isBetween(failureRecordedAt.plusSeconds(119),
                        failureRecordedAt.plusSeconds(121));
    }

    private List<DataCleanupTaskUpdateRequest> updatesFrom(DataCleanupConfigResponse response) {
        return response.tasks().stream()
                .map(task -> new DataCleanupTaskUpdateRequest(
                        task.taskCode(),
                        task.enabled(),
                        task.retentionDays(),
                        task.batchSize(),
                        task.cronExpression(),
                        task.batchIntervalSeconds(),
                        task.uploadPendingGraceMinutes(),
                        task.retainReviews()
                ))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private int indexOf(
            List<DataCleanupTaskUpdateRequest> updates,
            DataCleanupTaskCode taskCode
    ) {
        for (int index = 0; index < updates.size(); index++) {
            if (updates.get(index).taskCode() == taskCode) {
                return index;
            }
        }
        throw new IllegalArgumentException("Missing task update: " + taskCode);
    }

    private DataCleanupTaskResponse task(
            DataCleanupConfigResponse response,
            DataCleanupTaskCode taskCode
    ) {
        return response.tasks().stream()
                .filter(task -> task.taskCode() == taskCode)
                .findFirst()
                .orElseThrow();
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }
}
