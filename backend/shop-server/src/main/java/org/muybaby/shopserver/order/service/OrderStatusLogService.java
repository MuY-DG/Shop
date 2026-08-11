package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardOutboxHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OrderStatusLogService {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusLogService.class);

    private final JdbcClient jdbcClient;
    private final WechatServiceCardOutboxHook serviceCardOutboxHook;

    public OrderStatusLogService(
            JdbcClient jdbcClient,
            WechatServiceCardOutboxHook serviceCardOutboxHook
    ) {
        this.jdbcClient = jdbcClient;
        this.serviceCardOutboxHook = serviceCardOutboxHook;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            Long orderId,
            String fromStatus,
            String toStatus,
            String eventType,
            String operatorType,
            Long operatorId,
            String description,
            LocalDateTime createdAt
    ) {
        record(
                orderId, null, fromStatus, toStatus, eventType,
                operatorType, operatorId, description, createdAt
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            Long orderId,
            Long afterSaleId,
            String fromStatus,
            String toStatus,
            String eventType,
            String operatorType,
            Long operatorId,
            String description,
            LocalDateTime createdAt
    ) {
        jdbcClient.sql("""
                        insert into order_status_log
                            (order_id, after_sale_id, from_status, to_status, event_type, operator_type,
                             operator_id, description, created_at)
                        values
                            (:orderId, :afterSaleId, :fromStatus, :toStatus, :eventType, :operatorType,
                             :operatorId, :description, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("afterSaleId", afterSaleId)
                .param("fromStatus", fromStatus == null ? "" : fromStatus)
                .param("toStatus", toStatus)
                .param("eventType", eventType)
                .param("operatorType", operatorType)
                .param("operatorId", operatorId)
                .param("description", description == null ? "" : description)
                .param("createdAt", createdAt == null ? LocalDateTime.now(java.time.ZoneOffset.UTC) : createdAt)
                .update();
        try {
            serviceCardOutboxHook.onOrderFact(
                    orderId,
                    createdAt == null ? LocalDateTime.now(java.time.ZoneOffset.UTC) : createdAt
            );
        } catch (RuntimeException ex) {
            // Service-card delivery is a non-critical side effect. Its configuration or payload
            // must never make the commerce state transition fail.
            log.warn(
                    "Unable to enqueue WeChat 2001 service-card fact: orderId={}, eventType={}, type={}",
                    orderId, eventType, ex.getClass().getSimpleName()
            );
        }
    }
}
