package org.muybaby.shopserver.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.error.RateLimitException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AppUserAvatarRateLimiterTest {

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearLimits() {
        jdbcClient.sql("delete from app_user_avatar_daily_limit").update();
    }

    @Test
    void limitUsesShanghaiCalendarDayAndReleasedFailuresDoNotConsumeQuota() {
        long userId = 990065L;
        Instant beforeMidnight = Instant.parse("2026-07-28T15:59:30Z");
        AppUserAvatarRateLimiter limiter = limiterAt(beforeMidnight);

        limiter.acquire(userId);
        limiter.acquire(userId);
        AppUserAvatarRateLimiter.Permit third = limiter.acquire(userId);

        assertThatThrownBy(() -> limiter.acquire(userId))
                .isInstanceOfSatisfying(RateLimitException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(ErrorCode.APP_USER_AVATAR_RATE_LIMITED);
                    assertThat(exception.retryAfterSeconds()).isEqualTo(30);
                });
        assertThat(changeCount(userId, LocalDate.of(2026, 7, 28))).isEqualTo(3);

        limiter.release(third);
        limiter.acquire(userId);
        assertThat(changeCount(userId, LocalDate.of(2026, 7, 28))).isEqualTo(3);

        AppUserAvatarRateLimiter nextDayLimiter =
                limiterAt(Instant.parse("2026-07-28T16:00:01Z"));
        nextDayLimiter.acquire(userId);
        assertThat(changeCount(userId, LocalDate.of(2026, 7, 29))).isEqualTo(1);
    }

    @Test
    void concurrentClaimsNeverExceedTheDailyLimit() throws Exception {
        int requestCount = 10;
        long userId = 990066L;
        AppUserAvatarRateLimiter limiter =
                limiterAt(Instant.parse("2026-07-28T08:00:00Z"));
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int request = 0; request < requestCount; request++) {
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.acquire(userId);
                        return true;
                    } catch (RateLimitException ex) {
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.APP_USER_AVATAR_RATE_LIMITED);
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            int allowed = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(AppUserAvatarRateLimiter.DAILY_LIMIT);
            assertThat(changeCount(userId, LocalDate.of(2026, 7, 28)))
                    .isEqualTo(AppUserAvatarRateLimiter.DAILY_LIMIT);
        } finally {
            executor.shutdownNow();
        }
    }

    private AppUserAvatarRateLimiter limiterAt(Instant instant) {
        return new AppUserAvatarRateLimiter(
                jdbcClient,
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private int changeCount(long userId, LocalDate limitDate) {
        return jdbcClient.sql("""
                        select change_count
                        from app_user_avatar_daily_limit
                        where user_id = :userId and limit_date = :limitDate
                        """)
                .param("userId", userId)
                .param("limitDate", limitDate)
                .query(Integer.class)
                .single();
    }
}
