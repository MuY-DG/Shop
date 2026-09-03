package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.realtime.AfterSaleChangedRealtimeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AfterSaleStatusLogService {

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher eventPublisher;

    public AfterSaleStatusLogService(
            JdbcClient jdbcClient,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            long afterSaleId,
            String fromStatus,
            String toStatus,
            String eventType,
            String operatorType,
            Long operatorId,
            String description,
            LocalDateTime createdAt
    ) {
        LocalDateTime occurredAt = createdAt == null
                ? LocalDateTime.now(java.time.ZoneOffset.UTC) : createdAt;
        jdbcClient.sql("""
                        insert into after_sale_status_log (
                            after_sale_id, from_status, to_status, event_type,
                            operator_type, operator_id, description, created_at
                        ) values (
                            :afterSaleId, :fromStatus, :toStatus, :eventType,
                            :operatorType, :operatorId, :description, :createdAt
                        )
                        """)
                .param("afterSaleId", afterSaleId)
                .param("fromStatus", empty(fromStatus))
                .param("toStatus", toStatus)
                .param("eventType", eventType)
                .param("operatorType", operatorType)
                .param("operatorId", operatorId)
                .param("description", truncate(description))
                .param("createdAt", occurredAt)
                .update();
        eventPublisher.publishEvent(new AfterSaleChangedRealtimeEvent(
                afterSaleId,
                empty(fromStatus),
                empty(toStatus),
                empty(eventType),
                occurredAt
        ));
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value) {
        String normalized = empty(value);
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }
}
