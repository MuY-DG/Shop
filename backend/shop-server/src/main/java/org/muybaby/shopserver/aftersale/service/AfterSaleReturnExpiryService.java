package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AfterSaleReturnExpiryService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleReturnExpiryService.class);
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 500;

    private final JdbcClient jdbcClient;
    private final AfterSaleStatusLogService statusLogService;
    private final OrderStatusLogService orderStatusLogService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public AfterSaleReturnExpiryService(
            JdbcClient jdbcClient,
            AfterSaleStatusLogService statusLogService,
            OrderStatusLogService orderStatusLogService,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.statusLogService = statusLogService;
        this.orderStatusLogService = orderStatusLogService;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(
            fixedDelayString = "${shop.after-sale.return-expiry-delay-ms:300000}",
            initialDelayString = "${shop.after-sale.return-expiry-initial-delay-ms:60000}"
    )
    public void expireScheduled() {
        try {
            expireDueReturns(DEFAULT_BATCH_SIZE);
        } catch (RuntimeException exception) {
            log.warn("Expired return after-sales could not be reconciled (type={})",
                    exception.getClass().getSimpleName());
        }
    }

    public int expireDueReturns(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Return expiry batch size must be between 1 and 500");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> ids = jdbcClient.sql("""
                        select id from after_sale_request
                        where status = :status
                          and return_deadline_at is not null
                          and return_deadline_at <= :now
                        order by return_deadline_at, id
                        limit :limit
                        """)
                .param("status", AfterSaleStatus.WAITING_RETURN.name())
                .param("now", now)
                .param("limit", batchSize)
                .query(Long.class)
                .list();
        int expired = 0;
        for (Long id : ids) {
            Boolean changed = requiresNewTransaction.execute(status -> expireOne(id, now));
            if (Boolean.TRUE.equals(changed)) {
                expired++;
            }
        }
        return expired;
    }

    public boolean expireDueForOrder(long orderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> ids = jdbcClient.sql("""
                        select id from after_sale_request
                        where order_id = :orderId
                          and status = :status
                          and return_deadline_at is not null
                          and return_deadline_at <= :now
                        order by id
                        """)
                .param("orderId", orderId)
                .param("status", AfterSaleStatus.WAITING_RETURN.name())
                .param("now", now)
                .query(Long.class)
                .list();
        boolean expired = false;
        for (Long id : ids) {
            expired |= Boolean.TRUE.equals(
                    requiresNewTransaction.execute(status -> expireOne(id, now)));
        }
        return expired;
    }

    private boolean expireOne(long afterSaleId, LocalDateTime now) {
        Route route = jdbcClient.sql("""
                        select id, order_id from after_sale_request where id = :id
                        """)
                .param("id", afterSaleId)
                .query((rs, rowNum) -> new Route(rs.getLong("id"), rs.getLong("order_id")))
                .optional()
                .orElse(null);
        if (route == null) {
            return false;
        }
        OrderState order = jdbcClient.sql("""
                        select id, status from shop_order where id = :id for update
                        """)
                .param("id", route.orderId())
                .query((rs, rowNum) -> new OrderState(
                        rs.getLong("id"), rs.getString("status")))
                .optional()
                .orElse(null);
        if (order == null) {
            return false;
        }
        ExpiryState state = jdbcClient.sql("""
                        select id, order_id, status, return_deadline_at
                        from after_sale_request where id = :id for update
                        """)
                .param("id", afterSaleId)
                .query((rs, rowNum) -> new ExpiryState(
                        rs.getLong("id"), rs.getLong("order_id"), rs.getString("status"),
                        rs.getObject("return_deadline_at", LocalDateTime.class)))
                .optional()
                .orElse(null);
        if (state == null || state.orderId() != order.orderId()
                || !AfterSaleStatus.WAITING_RETURN.name().equals(state.status())
                || state.returnDeadlineAt() == null || state.returnDeadlineAt().isAfter(now)) {
            return false;
        }
        int updated = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status, cancelled_at = :now,
                            version = version + 1, updated_at = :now
                        where id = :id and status = :expectedStatus
                          and return_deadline_at <= :now
                        """)
                .param("status", AfterSaleStatus.CANCELLED.name())
                .param("now", now)
                .param("id", afterSaleId)
                .param("expectedStatus", AfterSaleStatus.WAITING_RETURN.name())
                .update();
        if (updated != 1) {
            return false;
        }
        String description = "退货期限已过，售后自动关闭";
        statusLogService.record(
                afterSaleId, AfterSaleStatus.WAITING_RETURN.name(),
                AfterSaleStatus.CANCELLED.name(), "RETURN_EXPIRED",
                "SYSTEM", null, description, now);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                "RETURN_EXPIRED", "SYSTEM", null, description, now);
        return true;
    }

    private record Route(long afterSaleId, long orderId) {
    }

    private record OrderState(long orderId, String status) {
    }

    private record ExpiryState(
            long afterSaleId,
            long orderId,
            String status,
            LocalDateTime returnDeadlineAt
    ) {
    }
}
