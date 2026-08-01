package org.muybaby.shopserver.customerservice.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CustomerServiceRetentionService {

    private static final int MAX_BATCH_SIZE = 10_000;

    private final JdbcClient jdbcClient;

    public CustomerServiceRetentionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public int deleteBatchBefore(LocalDateTime cutoff, int batchSize) {
        if (cutoff == null || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Invalid customer-service retention batch");
        }
        List<Long> messageIds = jdbcClient.sql("""
                        select message.id
                        from customer_service_message message
                        join customer_service_conversation conversation
                          on conversation.id = message.conversation_id
                        where message.created_at < :cutoff
                          and (
                            message.consultation_no < conversation.consultation_no
                            or (
                              message.consultation_no = conversation.consultation_no
                              and conversation.status = 'CLOSED'
                              and conversation.closed_at < :cutoff
                            )
                          )
                        order by message.created_at asc, message.id asc
                        limit :batchSize
                        """)
                .param("cutoff", cutoff)
                .param("batchSize", batchSize)
                .query(Long.class)
                .list();
        if (messageIds.isEmpty()) {
            return 0;
        }

        jdbcClient.sql("""
                        update storage_asset asset
                        set expires_at = current_timestamp,
                            updated_at = current_timestamp
                        where asset.status = 'ACTIVE'
                          and asset.scope = 'ATTACHMENT'
                          and asset.media_kind = 'IMAGE'
                          and asset.id in (
                            select expired_message.resource_id
                            from customer_service_message expired_message
                            where expired_message.id in (:messageIds)
                              and expired_message.message_type = 'IMAGE'
                              and expired_message.resource_id is not null
                          )
                          and not exists (
                            select 1
                            from customer_service_message retained_message
                            where retained_message.resource_id = asset.id
                              and retained_message.id not in (:messageIds)
                          )
                        """)
                .param("messageIds", messageIds)
                .update();

        return jdbcClient.sql("delete from customer_service_message where id in (:messageIds)")
                .param("messageIds", messageIds)
                .update();
    }
}
