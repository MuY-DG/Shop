package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatServiceCardRuntimeMySqlConcurrencyTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("wechat_service_card_runtime_concurrency")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WechatServiceCardRuntimeSettingService runtimeSettingService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearRuntimeOverride() {
        jdbcClient.sql("delete from wechat_service_card_runtime_audit").update();
        jdbcClient.sql("delete from wechat_service_card_runtime_setting").update();
    }

    @Test
    void concurrentFirstInsertHasOneWinnerOneConflictAndOneAuditRevision() throws Exception {
        List<Outcome> outcomes = raceAtObservedVersion(
                0L, "first operator baseline", "second operator baseline"
        );

        assertOneWinnerAndOneConflict(outcomes, 1L);
        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_runtime_setting")
                .query(Long.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isOne();
        assertThat(jdbcClient.sql(
                        "select revision from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void concurrentSameRevisionUpdateHasOneWinnerAndAppendsOnlyItsAudit() throws Exception {
        runtimeSettingService.update(request(0L, "seed persisted baseline"), 1L);

        List<Outcome> outcomes = raceAtObservedVersion(
                1L, "first concurrent reason", "second concurrent reason"
        );

        assertOneWinnerAndOneConflict(outcomes, 2L);
        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isEqualTo(2L);
        String persistedReason = jdbcClient.sql("""
                        select change_reason from wechat_service_card_runtime_setting where id = 1
                        """)
                .query(String.class)
                .single();
        String auditedReason = jdbcClient.sql("""
                        select change_reason from wechat_service_card_runtime_audit
                        where revision = 2
                        """)
                .query(String.class)
                .single();
        assertThat(auditedReason).isEqualTo(persistedReason);
        assertThat(persistedReason).isIn(
                "first concurrent reason", "second concurrent reason"
        );
    }

    private List<Outcome> raceAtObservedVersion(
            long expectedVersion,
            String firstReason,
            String secondReason
    ) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> updateAfterSnapshot(
                    barrier, expectedVersion, firstReason, 1L
            ));
            Future<Outcome> second = executor.submit(() -> updateAfterSnapshot(
                    barrier, expectedVersion, secondReason, 1L
            ));
            return List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Outcome updateAfterSnapshot(
            CyclicBarrier barrier,
            long expectedVersion,
            String reason,
            long operatorId
    ) {
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    assertThat(runtimeSettingService.current().version())
                            .isEqualTo(expectedVersion);
                    await(barrier);
                    runtimeSettingService.update(
                            request(expectedVersion, reason), operatorId
                    );
                }));
        return new Outcome(reason, failure);
    }

    private void assertOneWinnerAndOneConflict(List<Outcome> outcomes, long version) {
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null)
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.failure())
                        .isInstanceOfSatisfying(BusinessException.class, failure ->
                                assertThat(failure.errorCode()).isEqualTo(
                                        ErrorCode.WECHAT_SERVICE_CARD_RUNTIME_CONFLICT
                                )));
        assertThat(runtimeSettingService.current().version()).isEqualTo(version);
    }

    private AdminWechatServiceCardRuntimeUpdateRequest request(long version, String reason) {
        return new AdminWechatServiceCardRuntimeUpdateRequest(
                false, false, version, reason
        );
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to synchronize runtime CAS race", ex);
        }
    }

    private record Outcome(String reason, Throwable failure) {
    }
}
