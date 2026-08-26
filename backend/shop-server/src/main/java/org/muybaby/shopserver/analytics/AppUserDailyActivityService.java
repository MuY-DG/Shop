package org.muybaby.shopserver.analytics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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

    public void record(long userId, Instant activeAt) {
        LocalDate activityDate = LocalDate.ofInstant(activeAt, BUSINESS_ZONE);
        jdbcClient.sql("""
                        INSERT INTO app_user_daily_activity (
                            user_id, activity_date, first_active_at
                        ) VALUES (
                            :userId, :activityDate, :activeAt
                        ) ON DUPLICATE KEY UPDATE user_id = user_id
                        """)
                .param("userId", userId)
                .param("activityDate", activityDate)
                .param("activeAt", activeAt)
                .update();
    }
}
