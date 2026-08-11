package org.muybaby.shopserver.wechat.servicecard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "shop.wechat.service-card-2001",
        name = "enabled",
        havingValue = "true"
)
public class WechatServiceCardRepairScheduler {

    private static final Logger log = LoggerFactory.getLogger(WechatServiceCardRepairScheduler.class);

    private final JdbcClient jdbcClient;
    private final WechatServiceCardProperties properties;
    private final WechatServiceCardRepairUnit repairUnit;
    private final Clock clock;
    private final AtomicLong paymentCursor = new AtomicLong(0L);

    public WechatServiceCardRepairScheduler(
            JdbcClient jdbcClient,
            WechatServiceCardProperties properties,
            WechatServiceCardRepairUnit repairUnit,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.repairUnit = repairUnit;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${shop.wechat.service-card-2001.repair-delay:1m}",
            initialDelayString = "${shop.wechat.service-card-2001.repair-initial-delay:30s}"
    )
    public void runOnce() {
        if (!properties.enabled() || !properties.imageConfigurationReady()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        List<RepairCandidate> candidates = jdbcClient.sql("""
                        select payment.id as payment_id, payment.order_id
                        from payment_order payment
                        join shop_order order_entry on order_entry.id = payment.order_id
                        left join wechat_service_card card on card.order_id = payment.order_id
                        where payment.status = 'PAID'
                          and payment.transaction_id <> ''
                          and payment.payer_openid <> ''
                          and payment.paid_at is not null
                          and payment.paid_at <= :now
                          and payment.id > :cursor
                          and (
                              (card.id is null and payment.paid_at >= :activationEarliest)
                              or (
                                  card.id is not null
                                  and card.terminal = false
                                  and card.send_blocked = false
                                  and (
                                      (card.activated_at is null
                                          and payment.paid_at >= :activationEarliest)
                                      or (card.remote_code_expire_at is not null
                                          and card.remote_code_expire_at >= :now)
                                      or (card.remote_code_expire_at is null
                                          and card.activated_at >= :updateEarliest)
                                  )
                              )
                          )
                        order by payment.id
                        limit :limit
                        """)
                .param("activationEarliest", now.minusHours(24))
                .param("updateEarliest", now.minusDays(30))
                .param("now", now)
                .param("cursor", paymentCursor.get())
                .param("limit", properties.batchSize())
                .query((rs, rowNum) -> new RepairCandidate(
                        rs.getLong("payment_id"), rs.getLong("order_id")
                ))
                .list();
        if (candidates.isEmpty()) {
            paymentCursor.set(0L);
            return;
        }
        for (RepairCandidate candidate : candidates) {
            paymentCursor.set(candidate.paymentId());
            try {
                repairUnit.repair(candidate.orderId(), now);
            } catch (RuntimeException ex) {
                log.warn(
                        "WeChat 2001 repair deferred: orderId={}, type={}",
                        candidate.orderId(), ex.getClass().getSimpleName()
                );
            }
        }
    }

    private record RepairCandidate(long paymentId, long orderId) {
    }
}
