package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.realtime.AfterSaleChangedRealtimeEvent;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardOutboxHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AfterSaleStatusLogService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleStatusLogService.class);

    private final JdbcClient jdbcClient;
    private final WechatServiceCardOutboxHook serviceCardOutboxHook;
    private final ApplicationEventPublisher eventPublisher;

    public AfterSaleStatusLogService(
            JdbcClient jdbcClient,
            WechatServiceCardOutboxHook serviceCardOutboxHook,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.serviceCardOutboxHook = serviceCardOutboxHook;
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
        try {
            serviceCardOutboxHook.onAfterSaleFact(afterSaleId, occurredAt);
        } catch (RuntimeException ex) {
            log.warn(
                    "Unable to enqueue WeChat 2001 after-sale fact: afterSaleId={}, eventType={}, type={}",
                    afterSaleId, eventType, ex.getClass().getSimpleName()
            );
        }
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value) {
        String normalized = empty(value);
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }
}
