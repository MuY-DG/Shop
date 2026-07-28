package org.muybaby.shopserver.user.service;

import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.error.RateLimitException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class AppUserAvatarRateLimiter {

    public static final int DAILY_LIMIT = 3;
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public AppUserAvatarRateLimiter(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public Permit acquire(long userId) {
        Instant now = clock.instant();
        LocalDate limitDate = LocalDate.ofInstant(now, BUSINESS_ZONE);
        if (incrementExisting(userId, limitDate, now)) {
            return new Permit(userId, limitDate);
        }
        try {
            jdbcClient.sql("""
                            INSERT INTO app_user_avatar_daily_limit (
                                user_id, limit_date, change_count, created_at, updated_at
                            ) VALUES (
                                :userId, :limitDate, 1, :now, :now
                            )
                            """)
                    .param("userId", userId)
                    .param("limitDate", limitDate)
                    .param("now", now)
                    .update();
            return new Permit(userId, limitDate);
        } catch (DuplicateKeyException ex) {
            if (incrementExisting(userId, limitDate, now)) {
                return new Permit(userId, limitDate);
            }
        }
        throw new RateLimitException(
                ErrorCode.APP_USER_AVATAR_RATE_LIMITED,
                secondsUntilNextBusinessDay(now)
        );
    }

    public void release(Permit permit) {
        jdbcClient.sql("""
                        UPDATE app_user_avatar_daily_limit
                        SET change_count = change_count - 1,
                            updated_at = :now
                        WHERE user_id = :userId
                          AND limit_date = :limitDate
                          AND change_count > 0
                        """)
                .param("now", clock.instant())
                .param("userId", permit.userId())
                .param("limitDate", permit.limitDate())
                .update();
    }

    public int remaining(Permit permit) {
        Integer changeCount = jdbcClient.sql("""
                        SELECT change_count
                        FROM app_user_avatar_daily_limit
                        WHERE user_id = :userId AND limit_date = :limitDate
                        """)
                .param("userId", permit.userId())
                .param("limitDate", permit.limitDate())
                .query(Integer.class)
                .single();
        return Math.max(0, DAILY_LIMIT - changeCount);
    }

    private boolean incrementExisting(long userId, LocalDate limitDate, Instant now) {
        return jdbcClient.sql("""
                        UPDATE app_user_avatar_daily_limit
                        SET change_count = change_count + 1,
                            updated_at = :now
                        WHERE user_id = :userId
                          AND limit_date = :limitDate
                          AND change_count < :dailyLimit
                        """)
                .param("now", now)
                .param("userId", userId)
                .param("limitDate", limitDate)
                .param("dailyLimit", DAILY_LIMIT)
                .update() > 0;
    }

    private long secondsUntilNextBusinessDay(Instant now) {
        ZonedDateTime current = now.atZone(BUSINESS_ZONE);
        ZonedDateTime nextDay = current.toLocalDate().plusDays(1).atStartOfDay(BUSINESS_ZONE);
        return Math.max(1L, Duration.between(current, nextDay).toSeconds());
    }

    public record Permit(long userId, LocalDate limitDate) {
    }
}
