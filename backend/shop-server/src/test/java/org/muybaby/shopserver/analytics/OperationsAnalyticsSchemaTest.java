package org.muybaby.shopserver.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OperationsAnalyticsSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void analyticsActivityPaymentAttemptAndOperatingSnapshotSchemaExist() {
        jdbcClient.sql("delete from app_user_daily_activity where user_id = 990032 and activity_date = current_date")
                .update();
        jdbcClient.sql("""
                        insert into analytics_event
                            (client_event_id, payload_digest, visitor_id, session_id, event_source,
                             event_type, page_path, occurred_at, business_date)
                        values
                            ('schema-event', 'schema-digest', 'schema-visitor', 'schema-session', 'CLIENT',
                             'PAGE_VIEW', '/pages/home/home', current_timestamp, current_date)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into app_user_daily_activity
                            (user_id, activity_date, first_active_at, last_active_at, request_count)
                        values (990032, current_date, current_timestamp, current_timestamp, 1)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into payment_attempt
                            (order_id, out_trade_no, status, amount_cent, started_at)
                        values (990032, 'SCHEMA-PAY-ATTEMPT', 'STARTED', 3980, current_timestamp)
                        """)
                .update();

        Integer analyticsCount = jdbcClient.sql("select count(*) from analytics_event where client_event_id = 'schema-event'")
                .query(Integer.class)
                .single();
        Integer activityCount = jdbcClient.sql("""
                        select count(*) from app_user_daily_activity
                        where user_id = 990032 and activity_date = current_date
                        """)
                .query(Integer.class)
                .single();
        Integer attemptCount = jdbcClient.sql("select count(*) from payment_attempt where out_trade_no = 'SCHEMA-PAY-ATTEMPT'")
                .query(Integer.class)
                .single();
        Integer lowStockThreshold = jdbcClient.sql("select low_stock_threshold from product_sku where id = 1")
                .query(Integer.class)
                .optional()
                .orElse(10);

        assertThat(analyticsCount).isEqualTo(1);
        assertThat(activityCount).isEqualTo(1);
        assertThat(attemptCount).isEqualTo(1);
        assertThat(lowStockThreshold).isEqualTo(10);
    }
}
