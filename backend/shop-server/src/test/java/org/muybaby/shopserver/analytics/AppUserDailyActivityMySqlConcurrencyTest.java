package org.muybaby.shopserver.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppUserDailyActivityMySqlConcurrencyTest {

    private static final long USER_ID = 990_033L;
    private static final int CONCURRENT_REQUESTS = 12;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("app_user_daily_activity_concurrency")
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
    private AppUserDailyActivityService activityService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearActivity() {
        jdbcClient.sql("delete from app_user_daily_activity where user_id = :userId")
                .param("userId", USER_ID)
                .update();
    }

    @Test
    void concurrentFirstRequestsCreateOneImmutableDailyFactWithoutFailure() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        List<Future<?>> futures = new ArrayList<>();
        Instant activeAt = Instant.parse("2026-08-26T03:58:43Z");
        try {
            for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
                int offset = index;
                futures.add(executor.submit(() -> {
                    await(barrier);
                    activityService.record(USER_ID, activeAt.plusMillis(offset));
                }));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        DailyFact fact = jdbcClient.sql("""
                        select count(*) as row_count, min(first_active_at) as first_active_at
                        from app_user_daily_activity
                        where user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query((rs, rowNum) -> new DailyFact(
                        rs.getLong("row_count"),
                        rs.getTimestamp("first_active_at").toInstant()))
                .single();
        assertThat(fact.rowCount()).isOne();
        assertThat(fact.firstActiveAt()).isBetween(
                activeAt,
                activeAt.plusMillis(CONCURRENT_REQUESTS - 1L)
        );

        activityService.record(USER_ID, activeAt.plusSeconds(60));
        Instant unchangedFirstActiveAt = jdbcClient.sql("""
                        select first_active_at
                        from app_user_daily_activity
                        where user_id = :userId
                        """)
                .param("userId", USER_ID)
                .query((rs, rowNum) -> rs.getTimestamp("first_active_at").toInstant())
                .single();
        assertThat(unchangedFirstActiveAt).isEqualTo(fact.firstActiveAt());
    }

    @Test
    void schemaContainsOnlyTheDailyActivityFactFields() {
        List<String> columns = jdbcClient.sql("""
                        select column_name
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'app_user_daily_activity'
                        order by ordinal_position
                        """)
                .query(String.class)
                .list();
        assertThat(columns).containsExactly("user_id", "activity_date", "first_active_at");
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record DailyFact(long rowCount, Instant firstActiveAt) {
    }
}
