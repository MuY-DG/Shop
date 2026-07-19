package org.muybaby.shopserver.analytics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class AnalyticsEventRetentionService {

    private final JdbcClient jdbcClient;

    public AnalyticsEventRetentionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public int deleteBatchBefore(LocalDate cutoffDate, int batchSize) {
        if (cutoffDate == null || batchSize < 1 || batchSize > 50_000) {
            throw new IllegalArgumentException("Invalid analytics retention batch");
        }
        List<Long> ids = jdbcClient.sql("""
                        SELECT id
                        FROM analytics_event
                        WHERE business_date < :cutoffDate
                        ORDER BY business_date ASC, id ASC
                        LIMIT :batchSize
                        """)
                .param("cutoffDate", cutoffDate)
                .param("batchSize", batchSize)
                .query(Long.class)
                .list();
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql("DELETE FROM analytics_event WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }
}
