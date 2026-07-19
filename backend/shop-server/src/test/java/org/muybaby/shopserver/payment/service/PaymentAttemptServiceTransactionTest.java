package org.muybaby.shopserver.payment.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentAttemptServiceTransactionTest {

    private static final String TRADE_NO_PREFIX = "TX-ATOMIC-";

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clearAttempts() {
        jdbcClient.sql("delete from payment_attempt where out_trade_no like :prefix")
                .param("prefix", TRADE_NO_PREFIX + "%")
                .update();
    }

    @AfterEach
    void cleanUpAttempts() {
        clearAttempts();
    }

    @Test
    void businessStateTransitionsRollBackWithTheirOuterTransaction() {
        long bindAttemptId = insertAttempt(TRADE_NO_PREFIX + "BIND", null);
        assertOuterRollback(() -> paymentAttemptService.bindPaymentOrder(
                bindAttemptId, 9_900_321L, LocalDateTime.now()));

        assertThat(jdbcClient.sql("select payment_order_id from payment_attempt where id = :attemptId")
                .param("attemptId", bindAttemptId)
                .query(Long.class)
                .optional()).isEmpty();

        long paidPaymentOrderId = 9_900_322L;
        long paidAttemptId = insertAttempt(TRADE_NO_PREFIX + "PAID", paidPaymentOrderId);
        assertOuterRollback(() -> paymentAttemptService.paid(paidPaymentOrderId, LocalDateTime.now()));

        assertAttemptUnchanged(paidAttemptId, "paid_at");

        long closedPaymentOrderId = 9_900_323L;
        long closedAttemptId = insertAttempt(TRADE_NO_PREFIX + "CLOSED", closedPaymentOrderId);
        assertOuterRollback(() -> paymentAttemptService.closed(closedPaymentOrderId, LocalDateTime.now()));

        assertAttemptUnchanged(closedAttemptId, "closed_at");
    }

    private long insertAttempt(String outTradeNo, Long paymentOrderId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into payment_attempt (
                            order_id, payment_order_id, out_trade_no, status, amount_cent,
                            started_at, prepay_succeeded_at, created_at, updated_at
                        ) values (
                            990032, :paymentOrderId, :outTradeNo, 'PREPAY_SUCCEEDED', 3980,
                            :now, :now, :now, :now
                        )
                        """)
                .param("paymentOrderId", paymentOrderId)
                .param("outTradeNo", outTradeNo)
                .param("now", now)
                .update();
        return jdbcClient.sql("select id from payment_attempt where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(Long.class)
                .single();
    }

    private void assertOuterRollback(Runnable transition) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            transition.run();
            throw new ForcedRollbackException();
        })).isInstanceOf(ForcedRollbackException.class);
    }

    private void assertAttemptUnchanged(long attemptId, String timestampColumn) {
        AttemptState state = jdbcClient.sql("""
                        select status, %s as transition_at
                        from payment_attempt
                        where id = :attemptId
                        """.formatted(timestampColumn))
                .param("attemptId", attemptId)
                .query((rs, rowNum) -> new AttemptState(
                        rs.getString("status"),
                        rs.getObject("transition_at", LocalDateTime.class)))
                .single();
        assertThat(state.status()).isEqualTo("PREPAY_SUCCEEDED");
        assertThat(state.transitionAt()).isNull();
    }

    private record AttemptState(String status, LocalDateTime transitionAt) {
    }

    private static final class ForcedRollbackException extends RuntimeException {
    }
}
