package org.muybaby.shopserver.admin.log.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminSystemLogRetentionService {

    private static final int MAX_BATCH_SIZE = 50_000;

    private final JdbcClient jdbcClient;

    public AdminSystemLogRetentionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public int deleteBatchBefore(LocalDateTime cutoff, int batchSize) {
        if (cutoff == null || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid admin system log retention batch");
        }
        List<Long> ids = jdbcClient.sql("""
                        select id
                        from admin_system_log
                        where occurred_at < :cutoff
                        order by occurred_at asc, id asc
                        limit :batchSize
                        """)
                .param("cutoff", cutoff)
                .param("batchSize", batchSize)
                .query(Long.class)
                .list();
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbcClient.sql("delete from admin_system_log where id in (:ids)")
                .param("ids", ids)
                .update();
    }
}
