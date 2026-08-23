package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.aftersale.OrderRefundStatus;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminExternalRefundApplyRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceResponse;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class ExternalRefundRegistrationService {

    private static final Set<String> RESTORABLE_ORDER_STATUSES = Set.of(
            OrderStatus.PAID.name(),
            OrderStatus.PARTIALLY_SHIPPED.name(),
            OrderStatus.SHIPPED.name(),
            OrderStatus.COMPLETED.name()
    );

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final FinanceReconciliationReadService readService;
    private final OrderStatusLogService orderStatusLogService;
    private final Clock clock;

    public ExternalRefundRegistrationService(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            FinanceReconciliationReadService readService,
            OrderStatusLogService orderStatusLogService,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.readService = readService;
        this.orderStatusLogService = orderStatusLogService;
        this.clock = clock;
    }

    public AdminReconciliationDifferenceResponse apply(
            long differenceId,
            AdminExternalRefundApplyRequest request,
            Long operatorId
    ) {
        try {
            transactionTemplate.executeWithoutResult(status -> applyLocked(
                    differenceId, request, operatorId));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
        }
        return readService.difference(differenceId);
    }

    private void applyLocked(
            long differenceId,
            AdminExternalRefundApplyRequest request,
            Long operatorId
    ) {
        long batchId = jdbcClient.sql("""
                        select batch_id
                        from finance_reconciliation_difference
                        where id = :differenceId
                        """)
                .param("differenceId", differenceId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        String mchId = jdbcClient.sql("""
                        select mch_id
                        from finance_reconciliation_batch
                        where id = :batchId
                        for update
                        """)
                .param("batchId", batchId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ExternalDifference difference = jdbcClient.sql("""
                        select id, batch_id, status, version, difference_type,
                               transaction_id, out_trade_no, refund_id, out_refund_no,
                               order_id, payment_order_id, provider_amount_cent,
                               provider_status, external_refund_applied
                        from finance_reconciliation_difference
                        where id = :differenceId and batch_id = :batchId
                        for update
                        """)
                .param("differenceId", differenceId)
                .param("batchId", batchId)
                .query(this::mapDifference)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        validateDifference(difference, request.version());

        TradeBillRefund billRefund = exactBillRefund(difference);
        ParentPayment payment = parentPayment(difference, mchId);
        validatePaymentIdentity(difference, payment, mchId);
        OrderState order = lockOrder(payment.orderId());
        if (difference.orderId() != null && difference.orderId() != order.id()) {
            throw conflict();
        }
        if (payment.amountCent() != order.paidAmountCent()
                || !"PAID".equals(payment.status())
                || !"CNY".equals(payment.currency())) {
            throw conflict();
        }
        rejectRefundThatHasBecomeLocal(difference, payment.id());

        long localRefunded = successfulLocalRefundAmount(order.id());
        long externallyRefunded = existingExternalRefundAmount(order.id());
        long refundedBefore = exactAdd(localRefunded, externallyRefunded);
        if (order.refundedAmountCent() > refundedBefore) {
            // Do not lower an aggregate that may contain legacy/manual accounting unknown to this
            // ledger. It must be investigated before a new external refund is registered.
            throw conflict();
        }
        long refundedAfter = exactAdd(refundedBefore, billRefund.amountCent());
        if (refundedAfter > order.paidAmountCent()) {
            throw conflict();
        }

        LocalDateTime now = now();
        long externalRefundId = insertExternalRefund(
                difference, billRefund, payment, mchId, request.reason().trim(), operatorId, now);
        boolean activeRefund = hasActiveRefund(order.id());
        boolean fullyRefunded = refundedAfter == order.paidAmountCent();
        String targetOrderStatus = fullyRefunded
                ? OrderStatus.REFUNDED.name()
                : restoreOrderStatus(order, activeRefund);
        String targetRefundStatus = fullyRefunded
                ? OrderRefundStatus.FULLY_REFUNDED.name()
                : activeRefund
                ? OrderRefundStatus.PARTIAL_REFUNDING.name()
                : OrderRefundStatus.PARTIALLY_REFUNDED.name();
        int orderRows = jdbcClient.sql("""
                        update shop_order
                        set status = :targetStatus,
                            refund_status = :refundStatus,
                            refunded_amount_cent = :refundedAmountCent,
                            last_refund_success_at = case
                                when last_refund_success_at is null
                                  or last_refund_success_at < :successAt then :successAt
                                else last_refund_success_at
                            end,
                            refunded_at = case when :fullyRefunded then :successAt else refunded_at end,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = :expectedStatus
                          and refunded_amount_cent = :expectedRefundedAmountCent
                        """)
                .param("targetStatus", targetOrderStatus)
                .param("refundStatus", targetRefundStatus)
                .param("refundedAmountCent", refundedAfter)
                .param("successAt", billRefund.occurredAt())
                .param("fullyRefunded", fullyRefunded)
                .param("updatedAt", now)
                .param("orderId", order.id())
                .param("expectedStatus", order.status())
                .param("expectedRefundedAmountCent", order.refundedAmountCent())
                .update();
        if (orderRows != 1) {
            throw conflict();
        }

        int differenceRows = jdbcClient.sql("""
                        update finance_reconciliation_difference
                        set status = 'RESOLVED',
                            order_id = :orderId,
                            payment_order_id = :paymentOrderId,
                            local_amount_cent = :amountCent,
                            local_status = 'EXTERNAL_REFUND_RECORDED',
                            resolution_code = 'EXTERNAL_REFUND_RECORDED',
                            resolution_reason = :reason,
                            resolved_by = :operatorId,
                            resolved_at = :resolvedAt,
                            external_refund_applied = true,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :differenceId
                          and version = :version
                          and external_refund_applied = false
                        """)
                .param("orderId", order.id())
                .param("paymentOrderId", payment.id())
                .param("amountCent", billRefund.amountCent())
                .param("reason", request.reason().trim())
                .param("operatorId", operatorId)
                .param("resolvedAt", now)
                .param("updatedAt", now)
                .param("differenceId", difference.id())
                .param("version", difference.version())
                .update();
        if (differenceRows != 1) {
            throw conflict();
        }
        refreshBatchOpenCount(batchId, now);
        audit(difference, request.reason().trim(), operatorId, externalRefundId, now);
        orderStatusLogService.record(
                order.id(), order.status(), targetOrderStatus,
                "EXTERNAL_REFUND_RECORDED", "ADMIN", operatorId,
                "财务对账登记微信商户平台退款：" + billRefund.amountCent() + "分", now);
    }

    private void validateDifference(ExternalDifference difference, long expectedVersion) {
        if (difference.version() != expectedVersion
                || difference.externalRefundApplied()
                || "AUTO_CLEARED".equals(difference.status())
                || !"CHANNEL_ONLY".equals(difference.type())
                || !"SUCCESS".equals(difference.providerStatus())
                || difference.providerAmountCent() == null
                || difference.providerAmountCent() < 1
                || (difference.refundId().isBlank() && difference.outRefundNo().isBlank())) {
            throw conflict();
        }
    }

    private TradeBillRefund exactBillRefund(ExternalDifference difference) {
        List<TradeBillRefund> rows = jdbcClient.sql("""
                        select occurred_at, amount_cent, currency, channel_status, row_digest
                        from wechat_trade_bill_entry
                        where batch_id = :batchId
                          and entry_type = 'REFUND'
                          and (:transactionId = '' or transaction_id = :transactionId)
                          and (:outTradeNo = '' or out_trade_no = :outTradeNo)
                          and (:refundId = '' or refund_id = :refundId)
                          and (:outRefundNo = '' or out_refund_no = :outRefundNo)
                        order by id
                        """)
                .param("batchId", difference.batchId())
                .param("transactionId", difference.transactionId())
                .param("outTradeNo", difference.outTradeNo())
                .param("refundId", difference.refundId())
                .param("outRefundNo", difference.outRefundNo())
                .query((rs, rowNum) -> new TradeBillRefund(
                        rs.getObject("occurred_at", LocalDateTime.class),
                        rs.getLong("amount_cent"),
                        rs.getString("currency"),
                        rs.getString("channel_status"),
                        rs.getString("row_digest")))
                .list();
        if (rows.size() != 1) {
            throw conflict();
        }
        TradeBillRefund row = rows.getFirst();
        if (row.amountCent() != difference.providerAmountCent()
                || !"CNY".equals(row.currency())
                || !"SUCCESS".equals(row.providerStatus())
                || row.rowDigest() == null || row.rowDigest().length() != 64) {
            throw conflict();
        }
        return row;
    }

    private ParentPayment parentPayment(ExternalDifference difference, String mchId) {
        List<ParentPayment> rows;
        if (difference.paymentOrderId() != null) {
            rows = jdbcClient.sql(parentPaymentSql() + " where payment.id = :paymentOrderId")
                    .param("paymentOrderId", difference.paymentOrderId())
                    .query(this::mapPayment)
                    .list();
        } else {
            if (difference.transactionId().isBlank() && difference.outTradeNo().isBlank()) {
                throw conflict();
            }
            rows = jdbcClient.sql(parentPaymentSql() + """
                            where (:transactionId = '' or payment.transaction_id = :transactionId)
                              and (:outTradeNo = '' or payment.out_trade_no = :outTradeNo)
                            order by payment.id
                            """)
                    .param("transactionId", difference.transactionId())
                    .param("outTradeNo", difference.outTradeNo())
                    .query(this::mapPayment)
                    .list();
        }
        if (rows.size() != 1 || !mchId.equals(rows.getFirst().mchId())) {
            throw conflict();
        }
        return rows.getFirst();
    }

    private String parentPaymentSql() {
        return """
                select payment.id, payment.order_id, payment.out_trade_no,
                       payment.transaction_id, payment.amount_cent, payment.currency,
                       payment.status,
                       case when payment.payment_config_id is not null
                            then config.mch_id else snapshot.mch_id end as mch_id
                from payment_order payment
                left join payment_config config on config.id = payment.payment_config_id
                left join payment_config_snapshot snapshot
                  on snapshot.fingerprint = payment.payment_config_fingerprint
                """;
    }

    private void validatePaymentIdentity(
            ExternalDifference difference,
            ParentPayment payment,
            String mchId
    ) {
        if (!mchId.equals(payment.mchId())
                || (!difference.transactionId().isBlank()
                && !difference.transactionId().equals(payment.transactionId()))
                || (!difference.outTradeNo().isBlank()
                && !difference.outTradeNo().equals(payment.outTradeNo()))) {
            throw conflict();
        }
    }

    private OrderState lockOrder(long orderId) {
        return jdbcClient.sql("""
                        select id, status, paid_amount_cent, refunded_amount_cent,
                               shipped_at, completed_at
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderState(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getLong("paid_amount_cent"),
                        rs.getLong("refunded_amount_cent"),
                        rs.getObject("shipped_at", LocalDateTime.class),
                        rs.getObject("completed_at", LocalDateTime.class)))
                .optional()
                .orElseThrow(this::conflict);
    }

    private void rejectRefundThatHasBecomeLocal(ExternalDifference difference, long paymentId) {
        long count = jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where payment_order_id = :paymentOrderId
                          and ((:refundId <> '' and refund_id = :refundId)
                            or (:outRefundNo <> '' and out_refund_no = :outRefundNo))
                        """)
                .param("paymentOrderId", paymentId)
                .param("refundId", difference.refundId())
                .param("outRefundNo", difference.outRefundNo())
                .query(Long.class)
                .single();
        if (count > 0) {
            throw conflict();
        }
    }

    private long successfulLocalRefundAmount(long orderId) {
        return jdbcClient.sql("""
                        select coalesce(sum(refund_amount_cent), 0)
                        from refund_order
                        where order_id = :orderId and status = 'SUCCESS'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long existingExternalRefundAmount(long orderId) {
        return jdbcClient.sql("""
                        select coalesce(sum(amount_cent), 0)
                        from finance_external_refund
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private boolean hasActiveRefund(long orderId) {
        return jdbcClient.sql("""
                        select count(*)
                        from after_sale_request
                        where order_id = :orderId and status = 'REFUNDING'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private String restoreOrderStatus(OrderState order, boolean activeRefund) {
        if (!OrderStatus.REFUNDING.name().equals(order.status()) || activeRefund) {
            return order.status();
        }
        String sourceStatus = jdbcClient.sql("""
                        select source_order_status
                        from after_sale_request
                        where order_id = :orderId
                          and status in ('REFUND_FAILED', 'CANCELLED', 'REJECTED', 'RETURN_REJECTED')
                          and source_order_status <> ''
                        order by id desc
                        limit 1
                        """)
                .param("orderId", order.id())
                .query(String.class)
                .optional()
                .orElse("");
        if (RESTORABLE_ORDER_STATUSES.contains(sourceStatus)) {
            return sourceStatus;
        }
        if (order.completedAt() != null) {
            return OrderStatus.COMPLETED.name();
        }
        if (order.shippedAt() != null) {
            return OrderStatus.SHIPPED.name();
        }
        return OrderStatus.PAID.name();
    }

    private long insertExternalRefund(
            ExternalDifference difference,
            TradeBillRefund billRefund,
            ParentPayment payment,
            String mchId,
            String reason,
            Long operatorId,
            LocalDateTime now
    ) {
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        int inserted = jdbcClient.sql("""
                        insert into finance_external_refund
                            (difference_id, batch_id, order_id, payment_order_id,
                             provider_identity_key, mch_id, transaction_id, out_trade_no,
                             refund_id, out_refund_no, amount_cent, currency, provider_status,
                             occurred_at, row_digest, reason, recorded_by, created_at)
                        values
                            (:differenceId, :batchId, :orderId, :paymentOrderId,
                             :identityKey, :mchId, :transactionId, :outTradeNo,
                             :refundId, :outRefundNo, :amountCent, :currency, :providerStatus,
                             :occurredAt, :rowDigest, :reason, :recordedBy, :createdAt)
                        """)
                .param("differenceId", difference.id())
                .param("batchId", difference.batchId())
                .param("orderId", payment.orderId())
                .param("paymentOrderId", payment.id())
                .param("identityKey", providerIdentityKey(
                        mchId, difference.refundId(), difference.outRefundNo()))
                .param("mchId", mchId)
                .param("transactionId", difference.transactionId())
                .param("outTradeNo", difference.outTradeNo())
                .param("refundId", difference.refundId())
                .param("outRefundNo", difference.outRefundNo())
                .param("amountCent", billRefund.amountCent())
                .param("currency", billRefund.currency())
                .param("providerStatus", billRefund.providerStatus())
                .param("occurredAt", billRefund.occurredAt())
                .param("rowDigest", billRefund.rowDigest())
                .param("reason", reason)
                .param("recordedBy", operatorId)
                .param("createdAt", now)
                .update(keyHolder, "id");
        if (inserted != 1 || keyHolder.getKey() == null) {
            throw conflict();
        }
        return keyHolder.getKey().longValue();
    }

    private void refreshBatchOpenCount(long batchId, LocalDateTime now) {
        jdbcClient.sql("""
                        update finance_reconciliation_batch
                        set open_difference_count = (
                                select count(*)
                                from finance_reconciliation_difference difference_entry
                                where difference_entry.batch_id = :batchId
                                  and difference_entry.status in ('OPEN', 'INVESTIGATING')
                            ),
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :batchId
                        """)
                .param("batchId", batchId)
                .param("updatedAt", now)
                .update();
    }

    private void audit(
            ExternalDifference difference,
            String reason,
            Long operatorId,
            long externalRefundId,
            LocalDateTime now
    ) {
        jdbcClient.sql("""
                        insert into finance_reconciliation_resolution_audit
                            (batch_id, difference_id, from_status, to_status, action,
                             resolution_code, reason, metadata, operator_id, created_at)
                        values
                            (:batchId, :differenceId, :fromStatus, 'RESOLVED', 'RESOLVE',
                             'EXTERNAL_REFUND_RECORDED', :reason, :metadata, :operatorId, :createdAt)
                        """)
                .param("batchId", difference.batchId())
                .param("differenceId", difference.id())
                .param("fromStatus", difference.status())
                .param("reason", reason)
                .param("metadata", "externalRefundId=" + externalRefundId)
                .param("operatorId", operatorId)
                .param("createdAt", now)
                .update();
    }

    private ExternalDifference mapDifference(ResultSet rs, int rowNum) throws SQLException {
        return new ExternalDifference(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getString("difference_type"),
                rs.getString("transaction_id"),
                rs.getString("out_trade_no"),
                rs.getString("refund_id"),
                rs.getString("out_refund_no"),
                rs.getObject("order_id", Long.class),
                rs.getObject("payment_order_id", Long.class),
                rs.getObject("provider_amount_cent", Long.class),
                rs.getString("provider_status"),
                rs.getBoolean("external_refund_applied")
        );
    }

    private ParentPayment mapPayment(ResultSet rs, int rowNum) throws SQLException {
        return new ParentPayment(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("out_trade_no"),
                rs.getString("transaction_id"),
                rs.getLong("amount_cent"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("mch_id")
        );
    }

    private long exactAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw conflict();
        }
    }

    private String providerIdentityKey(String mchId, String refundId, String outRefundNo) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (mchId + "|" + refundId + "|" + outRefundNo)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(TimePolicy.UTC));
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
    }

    private record ExternalDifference(
            long id,
            long batchId,
            String status,
            long version,
            String type,
            String transactionId,
            String outTradeNo,
            String refundId,
            String outRefundNo,
            Long orderId,
            Long paymentOrderId,
            Long providerAmountCent,
            String providerStatus,
            boolean externalRefundApplied
    ) {
    }

    private record TradeBillRefund(
            LocalDateTime occurredAt,
            long amountCent,
            String currency,
            String providerStatus,
            String rowDigest
    ) {
    }

    private record ParentPayment(
            long id,
            long orderId,
            String outTradeNo,
            String transactionId,
            long amountCent,
            String currency,
            String status,
            String mchId
    ) {
    }

    private record OrderState(
            long id,
            String status,
            long paidAmountCent,
            long refundedAmountCent,
            LocalDateTime shippedAt,
            LocalDateTime completedAt
    ) {
    }
}
