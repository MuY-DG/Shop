package org.muybaby.shopserver.wechat.servicecard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class WechatServiceCardOutboxHook {

    private final JdbcClient jdbcClient;
    private final WechatServiceCardOutboxService outboxService;

    public WechatServiceCardOutboxHook(
            JdbcClient jdbcClient,
            WechatServiceCardOutboxService outboxService
    ) {
        this.jdbcClient = jdbcClient;
        this.outboxService = outboxService;
    }

    /**
     * A savepoint isolates this non-critical integration from its commerce transaction. Successful
     * intents still commit atomically with the parent; a payload or outbox write failure rolls the
     * complete intent back to the savepoint before the caller catches it.
     */
    @Transactional(propagation = Propagation.NESTED)
    public void onOrderFact(long orderId, LocalDateTime eventTime) {
        outboxService.onOrderFact(orderId, eventTime);
    }

    @Transactional(propagation = Propagation.NESTED)
    public void onAfterSaleFact(long afterSaleId, LocalDateTime eventTime) {
        Long orderId = jdbcClient.sql("""
                        select order_id from after_sale_request where id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (orderId != null) {
            outboxService.onOrderFact(orderId, eventTime);
        }
    }
}
