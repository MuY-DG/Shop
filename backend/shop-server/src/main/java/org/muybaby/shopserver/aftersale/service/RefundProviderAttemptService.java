package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.dto.AdminRefundProviderAttemptResponse;
import org.muybaby.shopserver.common.error.ProviderFailureCode;
import org.muybaby.shopserver.common.web.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RefundProviderAttemptService {

    private static final Logger log = LoggerFactory.getLogger(RefundProviderAttemptService.class);

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public RefundProviderAttemptService(
            JdbcClient jdbcClient,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordRefund(
            Long refundOrderId,
            String attemptType,
            String source,
            String result,
            String providerStatus,
            String decision,
            Throwable failure
    ) {
        recordSafely(() -> {
            AttemptIdentity identity = jdbcClient.sql("""
                            select ro.id, ro.after_sale_id, ro.order_id, ro.out_refund_no,
                                   po.out_trade_no
                            from refund_order ro
                            join payment_order po on po.id = ro.payment_order_id
                            where ro.id = :refundOrderId
                            """)
                    .param("refundOrderId", refundOrderId)
                    .query(this::mapIdentity)
                    .optional()
                    .orElse(null);
            if (identity != null) {
                insert(identity, attemptType, source, result, providerStatus, decision, failure);
            }
        });
    }

    public void recordPreflight(
            Long afterSaleId,
            Long orderId,
            String outTradeNo,
            String source,
            String result,
            String providerStatus,
            String decision,
            Throwable failure
    ) {
        recordSafely(() -> insert(
                new AttemptIdentity(null, afterSaleId, orderId, outTradeNo, ""),
                "ORDER_PREFLIGHT", source, result, providerStatus, decision, failure));
    }

    public List<AdminRefundProviderAttemptResponse> findByAfterSaleId(Long afterSaleId) {
        return jdbcClient.sql("""
                        select id, refund_order_id, after_sale_id, order_id,
                               out_trade_no, out_refund_no, attempt_type, source, result,
                               provider_http_status, provider_error_code, provider_status,
                               decision, request_id, created_at
                        from refund_provider_attempt
                        where after_sale_id = :afterSaleId
                        order by created_at asc, id asc
                        """)
                .param("afterSaleId", afterSaleId)
                .query(this::mapResponse)
                .list();
    }

    private void insert(
            AttemptIdentity identity,
            String attemptType,
            String source,
            String result,
            String providerStatus,
            String decision,
            Throwable failure
    ) {
        jdbcClient.sql("""
                        insert into refund_provider_attempt (
                            refund_order_id, after_sale_id, order_id,
                            out_trade_no, out_refund_no, attempt_type, source, result,
                            provider_http_status, provider_error_code, provider_status,
                            decision, request_id, created_at
                        ) values (
                            :refundOrderId, :afterSaleId, :orderId,
                            :outTradeNo, :outRefundNo, :attemptType, :source, :result,
                            :providerHttpStatus, :providerErrorCode, :providerStatus,
                            :decision, :requestId, :createdAt
                        )
                        """)
                .param("refundOrderId", identity.refundOrderId(), Types.BIGINT)
                .param("afterSaleId", identity.afterSaleId())
                .param("orderId", identity.orderId())
                .param("outTradeNo", text(identity.outTradeNo()))
                .param("outRefundNo", text(identity.outRefundNo()))
                .param("attemptType", text(attemptType))
                .param("source", text(source))
                .param("result", text(result))
                .param("providerHttpStatus", ProviderFailureCode.safeHttpStatus(failure), Types.INTEGER)
                .param("providerErrorCode", failure == null ? "" : ProviderFailureCode.safeCode(failure))
                .param("providerStatus", text(providerStatus))
                .param("decision", text(decision))
                .param("requestId", RequestLogContext.currentRequestId())
                .param("createdAt", LocalDateTime.now(clock))
                .update();
    }

    private void recordSafely(Runnable action) {
        try {
            requiresNew.executeWithoutResult(status -> action.run());
        } catch (RuntimeException exception) {
            log.warn("Refund provider attempt audit could not be persisted (type={})",
                    exception.getClass().getSimpleName());
        }
    }

    private AttemptIdentity mapIdentity(ResultSet rs, int rowNum) throws SQLException {
        return new AttemptIdentity(
                rs.getObject("id", Long.class),
                rs.getLong("after_sale_id"),
                rs.getLong("order_id"),
                rs.getString("out_trade_no"),
                rs.getString("out_refund_no"));
    }

    private AdminRefundProviderAttemptResponse mapResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AdminRefundProviderAttemptResponse(
                rs.getLong("id"),
                rs.getObject("refund_order_id", Long.class),
                rs.getLong("after_sale_id"),
                rs.getLong("order_id"),
                rs.getString("out_trade_no"),
                rs.getString("out_refund_no"),
                rs.getString("attempt_type"),
                rs.getString("source"),
                rs.getString("result"),
                rs.getObject("provider_http_status", Integer.class),
                rs.getString("provider_error_code"),
                rs.getString("provider_status"),
                rs.getString("decision"),
                rs.getString("request_id"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private record AttemptIdentity(
            Long refundOrderId,
            Long afterSaleId,
            Long orderId,
            String outTradeNo,
            String outRefundNo
    ) {
    }
}
