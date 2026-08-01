package org.muybaby.shopserver.maintenance.cleanup;

import org.muybaby.shopserver.maintenance.cleanup.DataCleanupConfigService.DataCleanupClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.muybaby.shopserver.maintenance.cleanup.DataCleanupSchedulingConfiguration.SCHEDULER_BEAN_NAME;

@Component
@Profile("!test")
public class DataCleanupDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupDispatcher.class);
    private static final Duration LEASE_HEARTBEAT_INTERVAL = Duration.ofMinutes(5);

    private final DataCleanupConfigService configService;
    private final Map<DataCleanupTaskCode, DataCleanupExecutor> executors;
    private final TaskScheduler taskScheduler;

    public DataCleanupDispatcher(
            DataCleanupConfigService configService,
            List<DataCleanupExecutor> executors,
            @Qualifier(SCHEDULER_BEAN_NAME) TaskScheduler taskScheduler
    ) {
        this.configService = configService;
        this.taskScheduler = taskScheduler;
        this.executors = new EnumMap<>(DataCleanupTaskCode.class);
        for (DataCleanupExecutor executor : executors) {
            DataCleanupExecutor previous = this.executors.put(executor.taskCode(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate data cleanup executor: " + executor.taskCode());
            }
        }
    }

    @Scheduled(
            initialDelay = 15_000L,
            fixedDelay = 30_000L,
            scheduler = SCHEDULER_BEAN_NAME
    )
    public void dispatchDueTasks() {
        try {
            configService.initializeMissingSchedules();
            for (DataCleanupTaskCode taskCode : configService.dueTaskCodes()) {
                runOnce(taskCode);
            }
        } catch (RuntimeException ex) {
            log.warn("Data cleanup dispatcher scan failed; it will retry on the next tick", ex);
        }
    }

    void runOnce(DataCleanupTaskCode taskCode) {
        DataCleanupClaim claim = null;
        ScheduledFuture<?> heartbeat = null;
        AtomicBoolean leaseActive = new AtomicBoolean(true);
        try {
            claim = configService.claim(taskCode).orElse(null);
            if (claim == null) {
                return;
            }
            heartbeat = startLeaseHeartbeat(claim, leaseActive);
            DataCleanupExecutor executor = executors.get(taskCode);
            if (executor == null) {
                throw new IllegalStateException("Missing data cleanup executor: " + taskCode);
            }
            int processedCount = executor.execute(claim.setting(), leaseActive::get);
            if (!leaseActive.get()) {
                configService.fail(claim, new IllegalStateException("Data cleanup database lease was lost"));
                return;
            }
            configService.complete(claim, processedCount);
        } catch (RuntimeException ex) {
            log.warn("Data cleanup task failed: taskCode={}", taskCode, ex);
            if (claim != null) {
                try {
                    configService.fail(claim, ex);
                } catch (RuntimeException finalizeFailure) {
                    log.warn("Failed to record data cleanup task failure: taskCode={}", taskCode, finalizeFailure);
                }
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> startLeaseHeartbeat(
            DataCleanupClaim claim,
            AtomicBoolean leaseActive
    ) {
        return taskScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        if (!configService.renewLease(claim)) {
                            leaseActive.set(false);
                            log.warn(
                                    "Data cleanup task lost its database lease: taskCode={}",
                                    claim.setting().taskCode());
                        }
                    } catch (RuntimeException ex) {
                        leaseActive.set(false);
                        log.warn(
                                "Failed to renew data cleanup task lease: taskCode={}",
                                claim.setting().taskCode(),
                                ex);
                    }
                },
                Instant.now().plus(LEASE_HEARTBEAT_INTERVAL),
                LEASE_HEARTBEAT_INTERVAL
        );
    }
}
