package org.muybaby.shopserver.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventBatchRequest;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventBatchResponse;
import org.muybaby.shopserver.analytics.dto.AnalyticsEventRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AnalyticsEventServiceConcurrencyTest {

    private static final String VISITOR_ID = "00000000-0000-4000-8000-000000000091";
    private static final String SESSION_ID = "00000000-0000-4000-8000-000000000092";
    private static final String EVENT_ID = "00000000-0000-4000-8000-000000000093";

    @Autowired
    private AnalyticsEventService analyticsEventService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    @AfterEach
    void clearEvent() {
        jdbcClient.sql("delete from analytics_event where visitor_id = :visitorId")
                .param("visitorId", VISITOR_ID)
                .update();
    }

    @Test
    void concurrentIdenticalRetriesProduceOneFactAndOneDuplicateAcknowledgement() throws Exception {
        Instant occurredAt = Instant.now();
        AnalyticsEventBatchRequest request = new AnalyticsEventBatchRequest(
                VISITOR_ID,
                List.of(new AnalyticsEventRequest(
                        EVENT_ID,
                        SESSION_ID,
                        "PAGE_VIEW",
                        occurredAt,
                        "/pages/home/home",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<AnalyticsEventBatchResponse> first = executor.submit(() -> {
                start.await();
                return analyticsEventService.accept(null, request);
            });
            Future<AnalyticsEventBatchResponse> second = executor.submit(() -> {
                start.await();
                return analyticsEventService.accept(null, request);
            });
            start.countDown();

            List<AnalyticsEventBatchResponse> responses = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(responses).extracting(AnalyticsEventBatchResponse::acceptedCount)
                    .containsExactlyInAnyOrder(1, 0);
            assertThat(responses).extracting(AnalyticsEventBatchResponse::duplicateCount)
                    .containsExactlyInAnyOrder(0, 1);
            assertThat(jdbcClient.sql("select count(*) from analytics_event where visitor_id = :visitorId")
                    .param("visitorId", VISITOR_ID)
                    .query(Integer.class)
                    .single()).isEqualTo(1);
            assertThat(jdbcClient.sql("select event_source from analytics_event where visitor_id = :visitorId")
                    .param("visitorId", VISITOR_ID)
                    .query(String.class)
                    .single()).isEqualTo("CLIENT");
        } finally {
            executor.shutdownNow();
        }
    }
}
