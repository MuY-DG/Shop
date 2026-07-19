package org.muybaby.shopserver.payment.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class PaymentAttemptService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public PaymentAttemptService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long started(long orderId, String outTradeNo, long amountCent, LocalDateTime startedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into payment_attempt (
                            order_id, out_trade_no, status, amount_cent, started_at, created_at, updated_at
                        ) values (
                            :orderId, :outTradeNo, 'STARTED', :amountCent, :startedAt, :startedAt, :startedAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("outTradeNo", outTradeNo)
                        .addValue("amountCent", amountCent)
                        .addValue("startedAt", startedAt),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Payment attempt id was not generated");
        }
        return key.longValue();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepaySucceeded(long attemptId, LocalDateTime succeededAt) {
        updateAttemptStatus(attemptId, "PREPAY_SUCCEEDED", succeededAt, "prepay_succeeded_at");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepayFailed(long attemptId, RuntimeException failure, LocalDateTime failedAt) {
        int updated = jdbcClient.sql("""
                        update payment_attempt
                        set status = 'PREPAY_FAILED',
                            error_code = :errorCode,
                            error_message = :errorMessage,
                            updated_at = :failedAt
                        where id = :attemptId
                          and status = 'STARTED'
                        """)
                .param("errorCode", truncate(failure.getClass().getSimpleName(), 64))
                .param("errorMessage", "Provider prepay request failed")
                .param("failedAt", failedAt)
                .param("attemptId", attemptId)
                .update();
        requireUpdated(updated, attemptId, "prepay failure");
    }

    @Transactional
    public void bindPaymentOrder(long attemptId, long paymentOrderId, LocalDateTime updatedAt) {
        int updated = jdbcClient.sql("""
                        update payment_attempt
                        set payment_order_id = :paymentOrderId, updated_at = :updatedAt
                        where id = :attemptId
                          and status = 'PREPAY_SUCCEEDED'
                          and payment_order_id is null
                        """)
                .param("paymentOrderId", paymentOrderId)
                .param("updatedAt", updatedAt)
                .param("attemptId", attemptId)
                .update();
        requireUpdated(updated, attemptId, "payment order binding");
    }

    @Transactional
    public void paid(long paymentOrderId, LocalDateTime paidAt) {
        updatePaymentOrderStatus(paymentOrderId, "PAID", paidAt, "paid_at");
    }

    @Transactional
    public void closed(long paymentOrderId, LocalDateTime closedAt) {
        updatePaymentOrderStatus(paymentOrderId, "CLOSED", closedAt, "closed_at");
    }

    private void updateAttemptStatus(long attemptId, String status, LocalDateTime changedAt, String timestampColumn) {
        if (!("prepay_succeeded_at".equals(timestampColumn)
                || "paid_at".equals(timestampColumn)
                || "closed_at".equals(timestampColumn))) {
            throw new IllegalArgumentException("Unsupported payment attempt timestamp");
        }
        int updated = jdbcClient.sql("""
                        update payment_attempt
                        set status = :status,
                            %s = :changedAt,
                            updated_at = :changedAt
                        where id = :attemptId
                          and status = 'STARTED'
                        """.formatted(timestampColumn))
                .param("status", status)
                .param("changedAt", changedAt)
                .param("attemptId", attemptId)
                .update();
        requireUpdated(updated, attemptId, status);
    }

    private void updatePaymentOrderStatus(
            long paymentOrderId,
            String status,
            LocalDateTime changedAt,
            String timestampColumn
    ) {
        if (!("paid_at".equals(timestampColumn) || "closed_at".equals(timestampColumn))) {
            throw new IllegalArgumentException("Unsupported payment attempt timestamp");
        }
        jdbcClient.sql("""
                        update payment_attempt
                        set status = :status,
                            %s = coalesce(%s, :changedAt),
                            updated_at = :changedAt
                        where payment_order_id = :paymentOrderId
                          and status in ('PREPAY_SUCCEEDED', :status)
                        """.formatted(timestampColumn, timestampColumn))
                .param("status", status)
                .param("changedAt", changedAt)
                .param("paymentOrderId", paymentOrderId)
                .update();
    }

    private void requireUpdated(int updated, long attemptId, String transition) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Payment attempt " + attemptId + " could not record " + transition);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
