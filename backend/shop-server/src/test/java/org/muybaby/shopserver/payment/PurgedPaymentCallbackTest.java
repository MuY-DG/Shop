package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.service.RefundCallbackService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.order.cleanup.PurgedOrderIdentityDigests;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.service.PaymentCallbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PurgedPaymentCallbackTest extends PaymentTestSupport {

    private static final long MANIFEST_ID = 9_871_100L;
    private static final String OUT_TRADE_NO = "PURGED-TRADE-9871100";
    private static final String OUT_REFUND_NO = "PURGED-REFUND-9871100";
    private static final String PAY_ROUTE = "p".repeat(32);
    private static final String REFUND_ROUTE = "r".repeat(32);

    @Autowired
    private PaymentConfigResolver paymentConfigResolver;

    @Autowired
    private PaymentCallbackService paymentCallbackService;

    @Autowired
    private RefundCallbackService refundCallbackService;

    @Test
    void verifiedLateCallbacksUseTombstonesAndDoNotRecreateRowsOrAuditNoise() {
        seedEnabledPaymentConfig();
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPaymentConfigId(91001L);
        String fingerprint = paymentConfigResolver.fingerprint(config);
        seedPurgedIdentities(fingerprint);

        paymentCallbackService.handlePayNotification(
                PAY_ROUTE,
                currentWechatpayTimestamp(),
                "nonce",
                "serial",
                "mock-valid-signature",
                payBody()
        );
        refundCallbackService.handleRefundNotification(
                REFUND_ROUTE,
                currentWechatpayTimestamp(),
                "nonce",
                "serial",
                "mock-valid-signature",
                refundBody()
        );

        assertThat(jdbcClient.sql("""
                        select count(*) from payment_callback_log
                        where out_trade_no = :outTradeNo or out_refund_no = :outRefundNo
                        """)
                .param("outTradeNo", OUT_TRADE_NO)
                .param("outRefundNo", OUT_REFUND_NO)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", OUT_TRADE_NO)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from refund_order where out_refund_no = :outRefundNo")
                .param("outRefundNo", OUT_REFUND_NO)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void latePaymentCallbackMustMatchPaidStateTransactionAndAmount() {
        seedEnabledPaymentConfig();
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPaymentConfigId(91001L);
        seedPurgedIdentities(paymentConfigResolver.fingerprint(config));

        jdbcClient.sql("""
                        update purged_payment_identity
                        set final_status = 'CLOSED'
                        where archive_manifest_id = :manifestId
                        """)
                .param("manifestId", MANIFEST_ID)
                .update();
        assertPayCallbackRejected(payBody());

        jdbcClient.sql("""
                        update purged_payment_identity
                        set final_status = 'PAID', amount_cent = 999
                        where archive_manifest_id = :manifestId
                        """)
                .param("manifestId", MANIFEST_ID)
                .update();
        assertPayCallbackRejected(payBody());

        jdbcClient.sql("""
                        update purged_payment_identity
                        set amount_cent = 1000, transaction_id_digest = :transactionDigest
                        where archive_manifest_id = :manifestId
                        """)
                .param("transactionDigest", PurgedOrderIdentityDigests.value("OTHER-TRANSACTION"))
                .param("manifestId", MANIFEST_ID)
                .update();
        assertPayCallbackRejected(payBody());
    }

    @Test
    void lateRefundCallbackMustMatchArchivedTerminalStateProviderIdAndAmount() {
        seedEnabledPaymentConfig();
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPaymentConfigId(91001L);
        seedPurgedIdentities(paymentConfigResolver.fingerprint(config));

        jdbcClient.sql("""
                        update purged_refund_identity
                        set final_status = 'FAILED', final_callback_status = 'CLOSED'
                        where archive_manifest_id = :manifestId
                        """)
                .param("manifestId", MANIFEST_ID)
                .update();
        assertRefundCallbackRejected(refundBody());

        jdbcClient.sql("""
                        update purged_refund_identity
                        set final_status = 'SUCCESS', final_callback_status = 'SUCCESS',
                            refund_amount_cent = 999
                        where archive_manifest_id = :manifestId
                        """)
                .param("manifestId", MANIFEST_ID)
                .update();
        assertRefundCallbackRejected(refundBody());

        jdbcClient.sql("""
                        update purged_refund_identity
                        set refund_amount_cent = 1000, refund_id_digest = :refundIdDigest
                        where archive_manifest_id = :manifestId
                        """)
                .param("refundIdDigest", PurgedOrderIdentityDigests.value("OTHER-REFUND"))
                .param("manifestId", MANIFEST_ID)
                .update();
        assertRefundCallbackRejected(refundBody());
    }

    @Test
    void matchingLateClosedRefundCallbackIsIdempotentlyAcknowledged() {
        seedEnabledPaymentConfig();
        ResolvedPaymentConfig config = paymentConfigResolver.resolveForPaymentConfigId(91001L);
        seedPurgedIdentities(paymentConfigResolver.fingerprint(config));
        jdbcClient.sql("""
                        update purged_refund_identity
                        set final_status = 'FAILED', final_callback_status = 'CLOSED'
                        where archive_manifest_id = :manifestId
                        """)
                .param("manifestId", MANIFEST_ID)
                .update();

        refundCallbackService.handleRefundNotification(
                REFUND_ROUTE,
                currentWechatpayTimestamp(),
                "nonce",
                "serial",
                "mock-valid-signature",
                refundBody("REFUND.CLOSED", "CLOSED")
        );

        assertThat(jdbcClient.sql("select count(*) from payment_callback_log")
                .query(Integer.class)
                .single()).isZero();
    }

    private void seedPurgedIdentities(String fingerprint) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into order_archive_manifest
                            (id, source_order_id, order_no_digest, object_key,
                             provider, storage_container, storage_region, content_type,
                             sha256, size_bytes, archive_format_version, status,
                             archived_at, purged_at, created_at, updated_at)
                        values
                            (:id, 9871101, :orderNoDigest, 'private/test/purged-payment.zip',
                             'TENCENT_COS', 'test-bucket-12345', 'ap-test',
                             'application/zip', :sha256, 1, 1, 'PURGED',
                             :now, :now, :now, :now)
                        """)
                .param("id", MANIFEST_ID)
                .param("orderNoDigest", PurgedOrderIdentityDigests.value("ORDER-9871101"))
                .param("sha256", PurgedOrderIdentityDigests.value("archive"))
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into purged_payment_identity
                            (archive_manifest_id, out_trade_no_digest, transaction_id_digest,
                             notification_route_digest, payment_config_id,
                             payment_config_fingerprint, final_status, amount_cent, currency,
                             purged_at, created_at)
                        values
                            (:manifestId, :tradeDigest, :transactionDigest, :routeDigest,
                             91001, :fingerprint, 'PAID', 1000, 'CNY', :now, :now)
                        """)
                .param("manifestId", MANIFEST_ID)
                .param("tradeDigest", PurgedOrderIdentityDigests.value(OUT_TRADE_NO))
                .param("transactionDigest", PurgedOrderIdentityDigests.value("WX-PURGED-TRADE"))
                .param("routeDigest", PurgedOrderIdentityDigests.value(PAY_ROUTE))
                .param("fingerprint", fingerprint)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into purged_refund_identity
                            (archive_manifest_id, out_refund_no_digest, out_trade_no_digest,
                             refund_id_digest, notification_route_digest, payment_config_id,
                             payment_config_fingerprint, final_status, final_callback_status,
                             refund_amount_cent, purged_at, created_at)
                        values
                            (:manifestId, :refundDigest, :tradeDigest, :refundIdDigest, :routeDigest,
                             91001, :fingerprint, 'SUCCESS', 'SUCCESS', 1000, :now, :now)
                        """)
                .param("manifestId", MANIFEST_ID)
                .param("refundDigest", PurgedOrderIdentityDigests.value(OUT_REFUND_NO))
                .param("tradeDigest", PurgedOrderIdentityDigests.value(OUT_TRADE_NO))
                .param("refundIdDigest", PurgedOrderIdentityDigests.value("WX-PURGED-REFUND"))
                .param("routeDigest", PurgedOrderIdentityDigests.value(REFUND_ROUTE))
                .param("fingerprint", fingerprint)
                .param("now", now)
                .update();
    }

    private String payBody() {
        return """
                {
                  "id":"late-pay",
                  "event_type":"TRANSACTION.SUCCESS",
                  "resource":{
                    "out_trade_no":"%s",
                    "transaction_id":"WX-PURGED-TRADE",
                    "trade_state":"SUCCESS",
                    "success_time":"2026-07-08T12:00:00+08:00",
                    "amount":{"total":1000,"payer_total":1000,"currency":"CNY"}
                  }
                }
                """.formatted(OUT_TRADE_NO);
    }

    private String refundBody() {
        return refundBody("REFUND.SUCCESS", "SUCCESS");
    }

    private String refundBody(String eventType, String refundStatus) {
        return """
                {
                  "id":"late-refund",
                  "event_type":"%s",
                  "resource":{
                    "out_trade_no":"%s",
                    "out_refund_no":"%s",
                    "refund_id":"WX-PURGED-REFUND",
                    "refund_status":"%s",
                    "success_time":"2026-07-08T14:00:00+08:00",
                    "amount":{"refund":1000,"total":1000,"currency":"CNY"}
                  }
                }
                """.formatted(eventType, OUT_TRADE_NO, OUT_REFUND_NO, refundStatus);
    }

    private void assertPayCallbackRejected(String body) {
        assertThatThrownBy(() -> paymentCallbackService.handlePayNotification(
                PAY_ROUTE,
                currentWechatpayTimestamp(),
                "nonce",
                "serial",
                "mock-valid-signature",
                body
        )).isInstanceOf(BusinessException.class);
    }

    private void assertRefundCallbackRejected(String body) {
        assertThatThrownBy(() -> refundCallbackService.handleRefundNotification(
                REFUND_ROUTE,
                currentWechatpayTimestamp(),
                "nonce",
                "serial",
                "mock-valid-signature",
                body
        )).isInstanceOf(BusinessException.class);
    }
}
