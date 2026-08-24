package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminExternalRefundApplyRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalRefundRegistrationServiceTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 10, 6, 30);

    @Autowired
    private ExternalRefundRegistrationService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void recordsVerifiedChannelOnlyRefundWithoutForgingAProviderRefundOrder() {
        Seed seed = seed(9_470_100L, "PAID", false);

        var result = service.apply(
                seed.differenceId(),
                new AdminExternalRefundApplyRequest(0L, "微信账单确认商户平台退款"),
                1L
        );

        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.resolutionCode()).isEqualTo("EXTERNAL_REFUND_RECORDED");
        assertThat(result.externalRefundApplied()).isTrue();
        assertThat(result.orderId()).isEqualTo(seed.orderId());
        assertThat(result.paymentOrderId()).isEqualTo(seed.paymentId());
        assertThat(result.refundOrderId()).isNull();
        assertThat(jdbcClient.sql("""
                        select concat(status, '|', refund_status, '|', refunded_amount_cent)
                        from shop_order where id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo("PAID|PARTIALLY_REFUNDED|52");
        assertThat(jdbcClient.sql("""
                        select concat(amount_cent, '|', currency, '|', provider_status, '|', recorded_by)
                        from finance_external_refund where difference_id = :differenceId
                        """)
                .param("differenceId", seed.differenceId())
                .query(String.class)
                .single()).isEqualTo("52|CNY|SUCCESS|1");
        assertThat(jdbcClient.sql("select count(*) from refund_order where order_id = :orderId")
                .param("orderId", seed.orderId())
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_resolution_audit
                        where difference_id = :differenceId
                          and action = 'RESOLVE'
                          and resolution_code = 'EXTERNAL_REFUND_RECORDED'
                        """)
                .param("differenceId", seed.differenceId())
                .query(Long.class)
                .single()).isEqualTo(1L);

        assertThatThrownBy(() -> service.apply(
                seed.differenceId(),
                new AdminExternalRefundApplyRequest(1L, "重复登记"),
                1L))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo(ErrorCode.FINANCE_RECONCILIATION_CONFLICT));
    }

    @Test
    void partialExternalRefundRestoresAFailedFullRefundOrderToItsSourceStatus() {
        Seed seed = seed(9_470_200L, "REFUNDING", true);

        service.apply(
                seed.differenceId(),
                new AdminExternalRefundApplyRequest(0L, "确认外部退款 0.52 元，原系统退款仍失败"),
                1L
        );

        assertThat(jdbcClient.sql("""
                        select concat(status, '|', refund_status, '|', refunded_amount_cent)
                        from shop_order where id = :orderId
                        """)
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo("COMPLETED|PARTIALLY_REFUNDED|52");
        assertThat(jdbcClient.sql("select status from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", seed.afterSaleId())
                .query(String.class)
                .single()).isEqualTo("REFUND_FAILED");
    }

    private Seed seed(long base, String orderStatus, boolean failedAfterSale) {
        long configId = base + 1;
        long orderId = base + 2;
        long paymentId = base + 3;
        long batchId = base + 4;
        long differenceId = base + 5;
        long afterSaleId = base + 6;
        String mchId = "mch-external-" + base;
        String transactionId = "tx-external-" + base;
        String outTradeNo = "trade-external-" + base;
        String refundId = "wx-refund-" + base;
        String outRefundNo = "merchant-refund-" + base;

        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode, notify_url,
                             refund_notify_url, enabled, status,
                             secret_cipher_version, secret_key_id)
                        values
                            (:id, 'external-refund', 'wx-test', :mchId, 'serial',
                             'ciphertext', '', '', 'PUBLIC_KEY', 'https://notify.test/pay',
                             'https://notify.test/refund', true, 'ACTIVE', 2, 'test-v1')
                        """)
                .param("id", configId)
                .param("mchId", mchId)
                .update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             paid_amount_cent, refund_status, refunded_amount_cent,
                             paid_at, completed_at, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, :status, 'CART', :idempotencyKey,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             100, 'REFUND_FAILED', 0,
                             :paidAt, :completedAt, :createdAt, :updatedAt)
                        """)
                .param("id", orderId)
                .param("orderNo", "ORDER-" + base)
                .param("status", orderStatus)
                .param("idempotencyKey", "external-refund-" + base)
                .param("paidAt", OCCURRED_AT.minusDays(1))
                .param("completedAt", failedAfterSale ? OCCURRED_AT.minusHours(2) : null,
                        java.sql.Types.TIMESTAMP)
                .param("createdAt", OCCURRED_AT.minusDays(1))
                .param("updatedAt", OCCURRED_AT.minusDays(1))
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                             (id, order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, transaction_id, status, amount_cent, currency,
                             expires_at, paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :configId, :fingerprint,
                             :routeToken, :outTradeNo, :transactionId, 'PAID', 100, 'CNY',
                             :expiresAt, :paidAt, :createdAt, :updatedAt)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("configId", configId)
                .param("fingerprint", "7".repeat(64))
                .param("routeToken", org.muybaby.shopserver.support.PaymentFixtureIdentity.routeToken(paymentId))
                .param("outTradeNo", outTradeNo)
                .param("transactionId", transactionId)
                .param("expiresAt", OCCURRED_AT.minusHours(23))
                .param("paidAt", OCCURRED_AT.minusDays(1))
                .param("createdAt", OCCURRED_AT.minusDays(1))
                .param("updatedAt", OCCURRED_AT.minusDays(1))
                .update();
        if (failedAfterSale) {
            jdbcClient.sql("""
                            insert into after_sale_request
                                (id, after_sale_no, order_id, user_id, after_sale_type, status, reason,
                                 requested_amount_cent, source_order_status,
                                 created_at, updated_at)
                            values
                                (:id, :afterSaleNo, :orderId, 1, 'REFUND_ONLY', 'REFUND_FAILED', '退款失败',
                                 100, 'COMPLETED', :createdAt, :updatedAt)
                            """)
                    .param("id", afterSaleId)
                    .param("afterSaleNo", "AS" + afterSaleId)
                    .param("orderId", orderId)
                    .param("createdAt", OCCURRED_AT.minusHours(1))
                    .param("updatedAt", OCCURRED_AT.minusHours(1))
                    .update();
        }
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (id, mch_id, bill_date, bill_type, status, phase,
                             total_rows, refund_rows, difference_count, open_difference_count,
                             requested_at, created_at, updated_at)
                        values
                            (:id, :mchId, date '2026-08-10', 'TRADE_ALL', 'DIFFERENCES', 'COMPLETE',
                             1, 1, 1, 1, :now, :now, :now)
                        """)
                .param("id", batchId)
                .param("mchId", mchId)
                .param("now", OCCURRED_AT)
                .update();
        jdbcClient.sql("""
                        insert into wechat_trade_bill_entry
                            (batch_id, row_no, entry_type, transaction_id, out_trade_no,
                             refund_id, out_refund_no, occurred_at, amount_cent, currency,
                             channel_status, row_digest, created_at)
                        values
                            (:batchId, 1, 'REFUND', :transactionId, :outTradeNo,
                             :refundId, :outRefundNo, :occurredAt, 52, 'CNY',
                             'SUCCESS', :rowDigest, :createdAt)
                        """)
                .param("batchId", batchId)
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .param("refundId", refundId)
                .param("outRefundNo", outRefundNo)
                .param("occurredAt", OCCURRED_AT)
                .param("rowDigest", String.format("%064x", base))
                .param("createdAt", OCCURRED_AT)
                .update();
        jdbcClient.sql("""
                        insert into finance_reconciliation_difference
                            (id, batch_id, diff_key, difference_type, severity, status,
                             transaction_id, out_trade_no, refund_id, out_refund_no,
                             provider_amount_cent, provider_status,
                             provider_evidence, local_evidence, created_at, updated_at)
                        values
                            (:id, :batchId, :diffKey, 'CHANNEL_ONLY', 'CRITICAL', 'OPEN',
                             :transactionId, :outTradeNo, :refundId, :outRefundNo,
                             52, 'SUCCESS', '{}', '{}', :createdAt, :updatedAt)
                        """)
                .param("id", differenceId)
                .param("batchId", batchId)
                .param("diffKey", String.format("%064x", differenceId))
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .param("refundId", refundId)
                .param("outRefundNo", outRefundNo)
                .param("createdAt", OCCURRED_AT)
                .param("updatedAt", OCCURRED_AT)
                .update();
        return new Seed(orderId, paymentId, differenceId, afterSaleId);
    }

    private record Seed(long orderId, long paymentId, long differenceId, long afterSaleId) {
    }
}
