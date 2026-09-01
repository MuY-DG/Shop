package org.muybaby.shopserver.admin.log.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest
@ActiveProfiles("test")
class AdminSystemLogRetentionServiceTest {

    private static final String REQUEST_ID_PREFIX = "retention-system-log-";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminSystemLogRetentionService retentionService;

    @BeforeEach
    @AfterEach
    void clearLogs() {
        jdbcClient.sql("delete from admin_system_log where request_id like :requestIdPrefix")
                .param("requestIdPrefix", REQUEST_ID_PREFIX + "%")
                .update();
    }

    @Test
    void appliesShortAccessAndLongAuditRetentionInBoundedBatches() {
        insertLog("REQUEST", "old-access-1", LocalDateTime.of(1999, 1, 1, 0, 0));
        insertLog("REQUEST", "old-access-2", LocalDateTime.of(2001, 1, 1, 0, 0));
        insertLog("OPERATION", "old-operation", LocalDateTime.of(1999, 1, 2, 0, 0));
        insertLog("OPERATION", "kept-operation", LocalDateTime.of(2001, 1, 2, 0, 0));
        insertLog("REQUEST", "kept-access", LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(retentionService.deleteExpiredBatch(
                        LocalDateTime.of(2000, 2, 1, 0, 0),
                        LocalDateTime.of(2025, 12, 1, 0, 0),
                        2))
                .isEqualTo(2);
        assertThat(logRequestIds()).containsExactly(
                REQUEST_ID_PREFIX + "old-access-2",
                REQUEST_ID_PREFIX + "kept-operation",
                REQUEST_ID_PREFIX + "kept-access"
        );

        assertThat(retentionService.deleteExpiredBatch(
                        LocalDateTime.of(2000, 2, 1, 0, 0),
                        LocalDateTime.of(2025, 12, 1, 0, 0),
                        2))
                .isEqualTo(1);
        assertThat(logRequestIds()).containsExactly(
                REQUEST_ID_PREFIX + "kept-operation",
                REQUEST_ID_PREFIX + "kept-access"
        );
    }

    @Test
    void rejectsInvalidCutoffAndBatchSizes() {
        LocalDateTime cutoff = LocalDateTime.now();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteExpiredBatch(null, cutoff, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteExpiredBatch(cutoff, null, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteExpiredBatch(cutoff, cutoff, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> retentionService.deleteExpiredBatch(cutoff, cutoff, 50_001));
    }

    private void insertLog(String type, String suffix, LocalDateTime occurredAt) {
        jdbcClient.sql("""
                        insert into admin_system_log (
                            log_type, result, level, request_method, request_path,
                            http_status, duration_ms, client_ip, request_id, occurred_at
                        )
                        values (
                            :logType, 'SUCCESS', 'INFO', 'GET', '/admin/retention-test',
                            200, 1, '127.0.0.1', :requestId, :occurredAt
                        )
                        """)
                .param("logType", type)
                .param("requestId", REQUEST_ID_PREFIX + suffix)
                .param("occurredAt", occurredAt)
                .update();
    }

    private List<String> logRequestIds() {
        return jdbcClient.sql("""
                        select request_id
                        from admin_system_log
                        where request_id like :requestIdPrefix
                        order by occurred_at asc, id asc
                        """)
                .param("requestIdPrefix", REQUEST_ID_PREFIX + "%")
                .query(String.class)
                .list();
    }
}
