package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.order.service.OrderCloseService;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaymentTimeoutCloseService {

    private static final String OPERATOR_TYPE_SYSTEM = "SYSTEM";

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;
    private final OrderCloseService orderCloseService;
    private final PaymentAttemptService paymentAttemptService;

    public PaymentTimeoutCloseService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider,
            OrderCloseService orderCloseService,
            PaymentAttemptService paymentAttemptService
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
        this.orderCloseService = orderCloseService;
        this.paymentAttemptService = paymentAttemptService;
    }

    @Transactional
    public int closeExpiredPayments() {
        List<ExpiredPaymentRow> expiredPayments = jdbcClient.sql("""
                        select id as payment_order_id,
                               order_id,
                               out_trade_no
                        from payment_order
                        where status = 'PAYING'
                          and expires_at <= :now
                        order by expires_at asc, id asc
                        for update
                        """)
                .param("now", LocalDateTime.now())
                .query(this::mapExpiredPaymentRow)
                .list();
        if (expiredPayments.isEmpty()) {
            return 0;
        }
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        int closedCount = 0;
        for (ExpiredPaymentRow payment : expiredPayments) {
            wechatPayProvider.closeOrder(config, payment.outTradeNo());
            int updatedRows = jdbcClient.sql("""
                            update payment_order
                            set status = 'CLOSED',
                                closed_at = :closedAt,
                                updated_at = :updatedAt
                            where id = :paymentOrderId
                              and status = 'PAYING'
                            """)
                    .param("closedAt", LocalDateTime.now())
                    .param("updatedAt", LocalDateTime.now())
                    .param("paymentOrderId", payment.paymentOrderId())
                    .update();
            if (updatedRows == 1) {
                paymentAttemptService.closed(payment.paymentOrderId(), LocalDateTime.now());
                orderCloseService.closePayingOrder(payment.orderId(), "PAY_TIMEOUT", OPERATOR_TYPE_SYSTEM, 0L);
                closedCount++;
            }
        }
        return closedCount;
    }

    private ExpiredPaymentRow mapExpiredPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExpiredPaymentRow(
                rs.getLong("payment_order_id"),
                rs.getLong("order_id"),
                rs.getString("out_trade_no")
        );
    }

    private record ExpiredPaymentRow(Long paymentOrderId, Long orderId, String outTradeNo) {
    }
}
