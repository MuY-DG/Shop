package org.muybaby.shopserver.order.service;

import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.payment.PaymentTimeoutScanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CreatedOrderTimeoutCloseService {

    private static final Logger log = LoggerFactory.getLogger(CreatedOrderTimeoutCloseService.class);
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_CLAIM_CONTENTION_RETRIES = 8;

    private final JdbcClient jdbcClient;
    private final OrderCloseService orderCloseService;
    private final PaymentTimeoutScanProperties properties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public CreatedOrderTimeoutCloseService(
            JdbcClient jdbcClient,
            OrderCloseService orderCloseService,
            PaymentTimeoutScanProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.orderCloseService = orderCloseService;
        this.properties = properties;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public int closeExpiredCreatedOrders() {
        return closeExpiredCreatedOrders(properties.timeoutScanBatchSize());
    }

    public int closeExpiredCreatedOrders(int batchSize) {
        requireValidBatchSize(batchSize);
        Integer closed = withoutTransaction.execute(status -> closeOutsideTransaction(batchSize));
        return closed == null ? 0 : closed;
    }

    public boolean closeExpiredCreatedOrder(Long orderId) {
        requireOrderId(orderId);
        Boolean closed = withoutTransaction.execute(status -> {
            ClaimedOrder claimed = requiresNewTransaction.execute(
                    transactionStatus -> claimExpiredOrderOnce(orderId, now()));
            if (claimed == null) {
                return false;
            }
            try {
                return finalizeClaim(claimed);
            } catch (RuntimeException ex) {
                log.warn("One targeted expired created order could not be closed; its lease will be retried (type={})",
                        safeErrorCode(ex));
                return false;
            }
        });
        return Boolean.TRUE.equals(closed);
    }

    private int closeOutsideTransaction(int batchSize) {
        LocalDateTime scanTime = now();
        int closed = 0;
        for (int index = 0; index < batchSize; index++) {
            ClaimedOrder claimed = claimNext(scanTime);
            if (claimed == null) {
                break;
            }
            try {
                if (finalizeClaim(claimed)) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.warn("One expired created order could not be closed; its lease will be retried (type={})",
                        safeErrorCode(ex));
            }
        }
        return closed;
    }

    private ClaimedOrder claimNext(LocalDateTime scanTime) {
        for (int attempt = 0; attempt < MAX_CLAIM_CONTENTION_RETRIES; attempt++) {
            ClaimedOrder claimed = requiresNewTransaction.execute(status -> claimNextOnce(scanTime));
            if (claimed != null) {
                return claimed;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private ClaimedOrder claimNextOnce(LocalDateTime scanTime) {
        LocalDateTime claimedAt = now();
        LocalDateTime expiredClaimBefore = claimedAt.minus(properties.timeoutScanClaimTimeout());
        List<Long> candidates = jdbcClient.sql("""
                        select id
                        from shop_order
                        where status = 'CREATED'
                          and payment_expires_at is not null
                          and payment_expires_at <= :scanTime
                          and (
                            created_timeout_claim_token is null
                            or created_timeout_claimed_at is null
                            or created_timeout_claimed_at <= :expiredClaimBefore
                          )
                        order by payment_expires_at, id
                        limit 1
                        """)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .query(Long.class)
                .list();
        if (candidates.isEmpty()) {
            return null;
        }

        Long orderId = candidates.getFirst();
        String token = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update shop_order
                        set created_timeout_claim_token = :token,
                            created_timeout_claimed_at = :claimedAt,
                            created_timeout_attempts = created_timeout_attempts + 1,
                            updated_at = :claimedAt
                        where id = :orderId
                          and status = 'CREATED'
                          and payment_expires_at is not null
                          and payment_expires_at <= :scanTime
                          and (
                            created_timeout_claim_token is null
                            or created_timeout_claimed_at is null
                            or created_timeout_claimed_at <= :expiredClaimBefore
                          )
                        """)
                .param("token", token)
                .param("claimedAt", claimedAt)
                .param("orderId", orderId)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .update();
        return updated == 1 ? new ClaimedOrder(orderId, token) : null;
    }

    private ClaimedOrder claimExpiredOrderOnce(Long orderId, LocalDateTime scanTime) {
        LocalDateTime claimedAt = now();
        LocalDateTime expiredClaimBefore = claimedAt.minus(properties.timeoutScanClaimTimeout());
        String token = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update shop_order
                        set created_timeout_claim_token = :token,
                            created_timeout_claimed_at = :claimedAt,
                            created_timeout_attempts = created_timeout_attempts + 1,
                            updated_at = :claimedAt
                        where id = :orderId
                          and status = 'CREATED'
                          and payment_expires_at is not null
                          and payment_expires_at <= :scanTime
                          and (
                            created_timeout_claim_token is null
                            or created_timeout_claimed_at is null
                            or created_timeout_claimed_at <= :expiredClaimBefore
                          )
                        """)
                .param("token", token)
                .param("claimedAt", claimedAt)
                .param("orderId", orderId)
                .param("scanTime", scanTime)
                .param("expiredClaimBefore", expiredClaimBefore)
                .update();
        return updated == 1 ? new ClaimedOrder(orderId, token) : null;
    }

    private boolean finalizeClaim(ClaimedOrder claimed) {
        Boolean closed = requiresNewTransaction.execute(status -> {
            LocalDateTime closedAt = now();
            ClaimState current = jdbcClient.sql("""
                            select status, payment_expires_at, created_timeout_claim_token
                            from shop_order
                            where id = :orderId
                            for update
                            """)
                    .param("orderId", claimed.orderId())
                    .query(this::mapClaimState)
                    .optional()
                    .orElse(null);
            if (current == null
                    || !OrderStatus.CREATED.name().equals(current.status())
                    || current.paymentExpiresAt() == null
                    || current.paymentExpiresAt().isAfter(closedAt)
                    || !claimed.token().equals(current.claimToken())) {
                clearClaimIfOwned(claimed, closedAt);
                return false;
            }
            Long paymentCount = jdbcClient.sql("""
                            select count(*)
                            from payment_order
                            where order_id = :orderId
                            """)
                    .param("orderId", claimed.orderId())
                    .query(Long.class)
                    .single();
            if (paymentCount != 0L) {
                throw new IllegalStateException("A CREATED order cannot be timeout-closed while a payment row exists");
            }
            orderCloseService.closeCreatedOrder(
                    claimed.orderId(), "ORDER_PAYMENT_TIMEOUT", "SYSTEM", 0L);
            return true;
        });
        return Boolean.TRUE.equals(closed);
    }

    private void clearClaimIfOwned(ClaimedOrder claimed, LocalDateTime updatedAt) {
        jdbcClient.sql("""
                        update shop_order
                        set created_timeout_claim_token = null,
                            created_timeout_claimed_at = null,
                            updated_at = :updatedAt
                        where id = :orderId
                          and created_timeout_claim_token = :token
                        """)
                .param("updatedAt", updatedAt)
                .param("orderId", claimed.orderId())
                .param("token", claimed.token())
                .update();
    }

    private ClaimState mapClaimState(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimState(
                rs.getString("status"),
                rs.getObject("payment_expires_at", LocalDateTime.class),
                rs.getString("created_timeout_claim_token")
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private String safeErrorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name == null || name.isBlank() ? "RuntimeException" : name;
    }

    private void requireValidBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Created order timeout batch size must be between 1 and 500");
        }
    }

    private void requireOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("Order id must be positive");
        }
    }

    private record ClaimedOrder(Long orderId, String token) {
    }

    private record ClaimState(String status, LocalDateTime paymentExpiresAt, String claimToken) {
    }
}
