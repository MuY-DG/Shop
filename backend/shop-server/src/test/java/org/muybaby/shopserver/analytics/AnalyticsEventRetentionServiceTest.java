package org.muybaby.shopserver.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AnalyticsEventRetentionServiceTest {

    private static final String VISITOR_PREFIX = "retention-visitor-";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AnalyticsEventRetentionService retentionService;

    @BeforeEach
    @AfterEach
    void clearEvents() {
        jdbcClient.sql("delete from analytics_event where visitor_id like :visitorPrefix")
                .param("visitorPrefix", VISITOR_PREFIX + "%")
                .update();
    }

    @Test
    void deletesOnlyTheOldestExpiredRowsUpToTheConfiguredBatchSize() {
        insertEvent("old-1", LocalDate.of(2025, 1, 1));
        insertEvent("old-2", LocalDate.of(2025, 1, 2));
        insertEvent("old-3", LocalDate.of(2025, 1, 3));
        insertEvent("cutoff", LocalDate.of(2025, 2, 1));
        insertEvent("recent", LocalDate.of(2026, 1, 1));

        assertThat(retentionService.deleteBatchBefore(LocalDate.of(2025, 2, 1), 2)).isEqualTo(2);
        assertThat(eventIds()).containsExactly("old-3", "cutoff", "recent");

        assertThat(retentionService.deleteBatchBefore(LocalDate.of(2025, 2, 1), 2)).isEqualTo(1);
        assertThat(eventIds()).containsExactly("cutoff", "recent");
    }

    private void insertEvent(String eventId, LocalDate businessDate) {
        jdbcClient.sql("""
                        insert into analytics_event (
                            client_event_id, payload_digest, visitor_id, session_id, event_source,
                            event_type, page_path, occurred_at, received_at, business_date
                        ) values (
                            :eventId, :eventId, :visitorId, :sessionId, 'CLIENT',
                            'PAGE_VIEW', '/pages/home/home', current_timestamp, current_timestamp, :businessDate
                        )
                        """)
                .param("eventId", eventId)
                .param("visitorId", VISITOR_PREFIX + eventId)
                .param("sessionId", "retention-session-" + eventId)
                .param("businessDate", businessDate)
                .update();
    }

    private java.util.List<String> eventIds() {
        return jdbcClient.sql("""
                        select client_event_id
                        from analytics_event
                        where visitor_id like :visitorPrefix
                        order by business_date, id
                        """)
                .param("visitorPrefix", VISITOR_PREFIX + "%")
                .query(String.class)
                .list();
    }
}
