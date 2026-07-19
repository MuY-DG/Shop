package org.muybaby.shopserver.analytics;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class AppUserDailyActivityService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbcClient;

    public AppUserDailyActivityService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long userId, Instant activeAt) {
        LocalDate activityDate = LocalDate.ofInstant(activeAt, BUSINESS_ZONE);
        int updated = jdbcClient.sql("""
                        UPDATE app_user_daily_activity
                        SET first_active_at = least(first_active_at, :activeAt),
                            last_active_at = greatest(last_active_at, :activeAt),
                            request_count = request_count + 1,
                            updated_at = greatest(updated_at, :activeAt)
                        WHERE user_id = :userId AND activity_date = :activityDate
                        """)
                .param("activeAt", activeAt)
                .param("userId", userId)
                .param("activityDate", activityDate)
                .update();
        if (updated > 0) {
            return;
        }
        try {
            jdbcClient.sql("""
                            INSERT INTO app_user_daily_activity (
                                user_id, activity_date, first_active_at, last_active_at,
                                request_count, created_at, updated_at
                            ) VALUES (
                                :userId, :activityDate, :activeAt, :activeAt, 1, :activeAt, :activeAt
                            )
                            """)
                    .param("userId", userId)
                    .param("activityDate", activityDate)
                    .param("activeAt", activeAt)
                    .update();
        } catch (DuplicateKeyException ex) {
            jdbcClient.sql("""
                            UPDATE app_user_daily_activity
                            SET first_active_at = least(first_active_at, :activeAt),
                                last_active_at = greatest(last_active_at, :activeAt),
                                request_count = request_count + 1,
                                updated_at = greatest(updated_at, :activeAt)
                            WHERE user_id = :userId AND activity_date = :activityDate
                            """)
                    .param("activeAt", activeAt)
                    .param("userId", userId)
                    .param("activityDate", activityDate)
                    .update();
        }
    }
}
