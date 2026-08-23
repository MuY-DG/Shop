package org.muybaby.shopserver.finance.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceType;
import org.muybaby.shopserver.finance.reconciliation.TradeBillEntryType;
import org.muybaby.shopserver.finance.reconciliation.parser.ParsedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.parser.TradeBillRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeReconciliationMatcherTest {

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime BUSINESS_TIME = LocalDateTime.of(2026, 8, 1, 2, 0);
    private static final LocalDateTime HISTORICAL_TIME = LocalDateTime.of(2025, 1, 1, 2, 0);

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void resolvesDbAndEnvironmentMerchantOwnershipWithoutMixingIdentities() {
        insertDbConfig(93101L, "mch-db");
        insertEnvironmentSnapshot("e".repeat(64), "mch-env");
        insertOrder(93111L, "ORDER-DB");
        insertOrder(93112L, "ORDER-ENV");
        insertPayment(93121L, 93111L, 93101L, "d".repeat(64),
                "trade-db", "tx-db", "PAID", 1_000L, BUSINESS_TIME);
        insertPayment(93122L, 93112L, null, "e".repeat(64),
                "trade-env", "tx-env", "PAID", 2_000L, BUSINESS_TIME);

        TradeReconciliationResult db = matcher().compare(
                "mch-db", BILL_DATE, ParsedTradeBill.of(List.of(
                        paymentRow(1, "tx-db", "trade-db", 1_000L, "SUCCESS"))));
        TradeReconciliationResult env = matcher().compare(
                "mch-env", BILL_DATE, ParsedTradeBill.of(List.of(
                        paymentRow(1, "tx-env", "trade-env", 2_000L, "SUCCESS"))));

        assertThat(db.differences()).isEmpty();
        assertThat(db.localPaymentAmountCent()).isEqualTo(1_000L);
        assertThat(env.differences()).isEmpty();
        assertThat(env.localPaymentAmountCent()).isEqualTo(2_000L);
    }

    @Test
    void reloadsAnyStatusByBusinessIdSoMissedCallbackKeepsOrderLink() {
        insertDbConfig(93201L, "mch-callback");
        insertOrder(93211L, "ORDER-CALLBACK");
        insertPayment(93221L, 93211L, 93201L, "f".repeat(64),
                "trade-callback", "", "PAYING", 1_500L, null);

        TradeReconciliationResult result = matcher().compare(
                "mch-callback", BILL_DATE, ParsedTradeBill.of(List.of(
                        paymentRow(1, "tx-provider", "trade-callback", 1_500L, "SUCCESS"))));

        assertThat(result.differences())
                .extracting(DifferenceDraft::type)
                .contains(ReconciliationDifferenceType.IDENTITY_MISMATCH,
                        ReconciliationDifferenceType.STATUS_MISMATCH)
                .doesNotContain(ReconciliationDifferenceType.CHANNEL_ONLY);
        assertThat(result.differences())
                .allSatisfy(difference -> assertThat(difference.orderId()).isEqualTo(93211L));
    }

    @Test
    void matchesMultiplePartialRefundsAndAppliesDirectionalSnapshotStatusRules() {
        insertDbConfig(93301L, "mch-refund");
        insertOrder(93311L, "ORDER-REFUND");
        insertPayment(93321L, 93311L, 93301L, "1".repeat(64),
                "trade-refund", "tx-refund", "PAID", 1_000L, HISTORICAL_TIME);
        insertRefund(93331L, 93311L, 93321L, "out-refund-1", "refund-1", 300L, "PROCESSING");
        insertRefund(93332L, 93311L, 93321L, "out-refund-2", "refund-2", 200L, "SUCCESS");
        insertRefund(93333L, 93311L, 93321L, "out-refund-3", "refund-3", 100L, "FAILED");

        ParsedTradeBill bill = ParsedTradeBill.of(List.of(
                refundRow(1, "tx-refund", "trade-refund", "refund-1", "out-refund-1", 300L, "SUCCESS"),
                refundRow(2, "tx-refund", "trade-refund", "refund-2", "out-refund-2", 200L, "PROCESSING"),
                refundRow(3, "tx-refund", "trade-refund", "refund-3", "out-refund-3", 100L, "CHANGE")));

        TradeReconciliationResult result = matcher().compare("mch-refund", BILL_DATE, bill);

        assertThat(result.differences())
                .filteredOn(difference -> difference.type() == ReconciliationDifferenceType.STATUS_MISMATCH)
                .hasSize(2)
                .extracting(DifferenceDraft::outRefundNo)
                .containsExactlyInAnyOrder("out-refund-1", "out-refund-3");
        assertThat(result.differences())
                .extracting(DifferenceDraft::type)
                .doesNotContain(ReconciliationDifferenceType.CHANNEL_ONLY,
                        ReconciliationDifferenceType.LOCAL_ONLY);
        assertThat(result.localRefundAmountCent()).isEqualTo(600L);
    }

    @Test
    void duplicateChannelRowsCarryOrderGuardAndStableKeysIgnoreEvidenceChanges() {
        insertDbConfig(93401L, "mch-stable");
        insertOrder(93411L, "ORDER-STABLE");
        insertPayment(93421L, 93411L, 93401L, "2".repeat(64),
                "trade-stable", "tx-stable", "PAID", 100L, BUSINESS_TIME);

        TradeBillRow first = paymentRow(1, "tx-stable", "trade-stable", 120L, "SUCCESS");
        TradeBillRow duplicate = paymentRow(2, "tx-stable", "trade-stable", 120L, "SUCCESS");
        TradeReconciliationResult duplicated = matcher().compare(
                "mch-stable", BILL_DATE, ParsedTradeBill.of(List.of(first, duplicate)));
        DifferenceDraft duplicateDifference = duplicated.differences().stream()
                .filter(value -> value.type() == ReconciliationDifferenceType.DUPLICATE_CHANNEL_ROW)
                .findFirst()
                .orElseThrow();
        assertThat(duplicateDifference.orderId()).isEqualTo(93411L);
        assertThat(duplicateDifference.paymentOrderId()).isEqualTo(93421L);

        DifferenceDraft firstMismatch = duplicated.differences().stream()
                .filter(value -> value.type() == ReconciliationDifferenceType.AMOUNT_MISMATCH)
                .findFirst()
                .orElseThrow();
        DifferenceDraft changedEvidenceMismatch = matcher().compare(
                        "mch-stable", BILL_DATE, ParsedTradeBill.of(List.of(
                                paymentRow(1, "tx-stable", "trade-stable", 130L, "SUCCESS"))))
                .differences().stream()
                .filter(value -> value.type() == ReconciliationDifferenceType.AMOUNT_MISMATCH)
                .findFirst()
                .orElseThrow();
        assertThat(changedEvidenceMismatch.diffKey()).isEqualTo(firstMismatch.diffKey());
        assertThat(changedEvidenceMismatch.providerEvidence())
                .isNotEqualTo(firstMismatch.providerEvidence());
    }

    @Test
    void revokedRowsIgnoreBlankRefundIdentifiersAndUsePaymentIdentifiersForCandidates() throws Exception {
        insertDbConfig(93501L, "mch-revoked");
        insertOrder(93511L, "ORDER-REVOKED");
        insertPayment(93521L, 93511L, 93501L, "3".repeat(64),
                "trade-revoked", "tx-revoked", "PAID", 1_000L, HISTORICAL_TIME);
        insertRefund(93531L, 93511L, 93521L,
                "out-refund-target", "refund-target", 1_000L, "PROCESSING", HISTORICAL_TIME);

        List<Long> historicalRefundIds = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            long orderId = 93600L + index;
            long paymentId = 93800L + index;
            long refundOrderId = 94000L + index;
            historicalRefundIds.add(refundOrderId);
            insertOrder(orderId, "ORDER-HISTORY-" + index);
            insertPayment(paymentId, orderId, 93501L, "4".repeat(64),
                    "trade-history-" + index, "tx-history-" + index,
                    "PAID", 10L, HISTORICAL_TIME);
            insertRefund(refundOrderId, orderId, paymentId,
                    "out-refund-history-" + index, "", 10L, "PROCESSING", HISTORICAL_TIME);
        }

        TradeBillRow revoked = refundRow(
                1, "tx-revoked", "trade-revoked", "", "", 1_000L, "REVOKED");
        List<?> candidates = loadRefundCandidates("mch-revoked", List.of(revoked));
        TradeReconciliationResult result = matcher().compare(
                "mch-revoked", BILL_DATE, ParsedTradeBill.of(List.of(revoked)));

        assertThat(candidates).hasSize(1);
        assertThat(result.differences())
                .extracting(DifferenceDraft::type)
                .containsExactly(ReconciliationDifferenceType.IDENTITY_MISMATCH);
        assertThat(result.differences().getFirst().refundOrderId()).isEqualTo(93531L);
        assertThat(historicalRefundIds)
                .doesNotContain(result.differences().getFirst().refundOrderId());
    }

    @Test
    void completelyBlankRefundIdentityCannotMatchHistoricalBlankRefunds() throws Exception {
        insertDbConfig(94501L, "mch-blank");
        insertOrder(94511L, "ORDER-BLANK");
        insertPayment(94521L, 94511L, 94501L, "5".repeat(64),
                "trade-blank-history", "tx-blank-history", "PAID", 100L, HISTORICAL_TIME);
        insertRefund(94531L, 94511L, 94521L,
                "out-refund-blank-history", "", 100L, "PROCESSING", HISTORICAL_TIME);
        TradeBillRow allBlank = refundRow(1, "", "", "", "", 100L, "REVOKED");

        List<?> candidates = loadRefundCandidates("mch-blank", List.of(allBlank));
        TradeReconciliationResult result = matcher().compare(
                "mch-blank", BILL_DATE, ParsedTradeBill.of(List.of(allBlank)));

        assertThat(candidates).isEmpty();
        assertThat(result.differences())
                .singleElement()
                .satisfies(difference -> {
                    assertThat(difference.type()).isEqualTo(ReconciliationDifferenceType.CHANNEL_ONLY);
                    assertThat(difference.refundOrderId()).isNull();
                });
    }

    @Test
    void channelOnlyRefundKeepsItsProviderIdentityAndLinksTheOriginalPayment() {
        insertDbConfig(94601L, "mch-external-refund");
        insertOrder(94611L, "ORDER-EXTERNAL-REFUND");
        insertPayment(94621L, 94611L, 94601L, "6".repeat(64),
                "trade-external-refund", "tx-external-refund", "PAID", 1_000L, HISTORICAL_TIME);
        TradeBillRow externalRefund = refundRow(
                1,
                "tx-external-refund",
                "trade-external-refund",
                "wx-refund-external",
                "merchant-platform-refund",
                520L,
                "SUCCESS"
        );

        DifferenceDraft difference = matcher().compare(
                        "mch-external-refund",
                        BILL_DATE,
                        ParsedTradeBill.of(List.of(externalRefund)))
                .differences()
                .stream()
                .filter(value -> value.type() == ReconciliationDifferenceType.CHANNEL_ONLY)
                .findFirst()
                .orElseThrow();

        assertThat(difference.orderId()).isEqualTo(94611L);
        assertThat(difference.paymentOrderId()).isEqualTo(94621L);
        assertThat(difference.refundOrderId()).isNull();
        assertThat(difference.refundId()).isEqualTo("wx-refund-external");
        assertThat(difference.outRefundNo()).isEqualTo("merchant-platform-refund");
        assertThat(difference.localEvidence())
                .contains("\"paymentOrderId\":94621", "\"orderId\":94611");
    }

    @Test
    void largeDuplicateGroupUsesBoundedDigestSampleAndWholeGroupHash() throws Exception {
        List<TradeBillRow> duplicates = java.util.stream.LongStream.rangeClosed(1L, 1_200L)
                .mapToObj(rowNo -> paymentRow(
                        rowNo, "tx-large-duplicate", "trade-large-duplicate", 100L, "SUCCESS"))
                .toList();

        DifferenceDraft duplicate = matcher().compare(
                        "mch-no-local-large-duplicate",
                        BILL_DATE,
                        ParsedTradeBill.of(duplicates))
                .differences().stream()
                .filter(value -> value.type() == ReconciliationDifferenceType.DUPLICATE_CHANNEL_ROW)
                .findFirst()
                .orElseThrow();

        com.fasterxml.jackson.databind.JsonNode evidence =
                objectMapper.readTree(duplicate.providerEvidence());
        assertThat(duplicate.providerEvidence().length()).isLessThan(4_096);
        assertThat(evidence.path("duplicateCount").asInt()).isEqualTo(1_200);
        assertThat(evidence.path("rowDigestSample").size()).isEqualTo(10);
        assertThat(evidence.path("rowDigestGroupSha256").asText()).hasSize(64);
        assertThat(evidence.has("rowDigests")).isFalse();
    }

    private TradeReconciliationMatcher matcher() {
        return new TradeReconciliationMatcher(jdbcClient, objectMapper);
    }

    private List<?> loadRefundCandidates(String mchId, List<TradeBillRow> rows) throws Exception {
        Method method = TradeReconciliationMatcher.class.getDeclaredMethod(
                "refundCandidates", String.class, List.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(matcher(), mchId, rows);
    }

    private void insertDbConfig(long id, String mchId) {
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (:id, 'reconciliation', 'wx-test', :mchId, 'serial',
                             'ciphertext', '', '', 'PUBLIC_KEY', 'https://notify.test/pay',
                             'https://notify.test/refund', true, 'ACTIVE')
                        """)
                .param("id", id)
                .param("mchId", mchId)
                .update();
    }

    private void insertEnvironmentSnapshot(String fingerprint, String mchId) {
        jdbcClient.sql("""
                        insert into payment_config_snapshot
                            (fingerprint, config_source, config_name, app_id, mch_id,
                             merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, notify_url, refund_notify_url,
                             verify_mode, wechat_public_key_id,
                             wechat_public_key_pem_ciphertext)
                        values
                            (:fingerprint, 'ENV', 'environment', 'wx-test', :mchId,
                             'serial', 'ciphertext', 'private', 'https://notify.test/pay',
                             'https://notify.test/refund', 'PUBLIC_KEY', 'public-key-id', 'public')
                        """)
                .param("fingerprint", fingerprint)
                .param("mchId", mchId)
                .update();
    }

    private void insertOrder(long id, String orderNo) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             paid_amount_cent, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'CART', :idempotencyKey,
                             10000, :createdAt, :updatedAt)
                        """)
                .param("id", id)
                .param("orderNo", orderNo)
                .param("idempotencyKey", "reconciliation-" + id)
                .param("createdAt", BUSINESS_TIME)
                .param("updatedAt", BUSINESS_TIME)
                .update();
    }

    private void insertPayment(
            long id,
            long orderId,
            Long configId,
            String fingerprint,
            String outTradeNo,
            String transactionId,
            String status,
            long amountCent,
            LocalDateTime paidAt
    ) {
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, payment_config_fingerprint,
                             out_trade_no, transaction_id, status, amount_cent, expires_at,
                             paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :configId, :fingerprint, :outTradeNo,
                             :transactionId, :status, :amountCent, :expiresAt,
                             :paidAt, :createdAt, :updatedAt)
                        """)
                .param("id", id)
                .param("orderId", orderId)
                .param("configId", configId)
                .param("fingerprint", fingerprint)
                .param("outTradeNo", outTradeNo)
                .param("transactionId", transactionId)
                .param("status", status)
                .param("amountCent", amountCent)
                .param("expiresAt", BUSINESS_TIME.plusHours(1))
                .param("paidAt", paidAt)
                .param("createdAt", BUSINESS_TIME)
                .param("updatedAt", BUSINESS_TIME)
                .update();
    }

    private void insertRefund(
            long id,
            long orderId,
            long paymentId,
            String outRefundNo,
            String refundId,
            long amountCent,
            String status
    ) {
        insertRefund(id, orderId, paymentId, outRefundNo, refundId, amountCent, status, BUSINESS_TIME);
    }

    private void insertRefund(
            long id,
            long orderId,
            long paymentId,
            String outRefundNo,
            String refundId,
            long amountCent,
            String status,
            LocalDateTime requestedAt
    ) {
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id,
                             out_refund_no, refund_id, refund_amount_cent, status,
                             requested_at, created_at, updated_at)
                        values
                            (:id, :afterSaleId, :orderId, :paymentId,
                             :outRefundNo, :refundId, :amountCent, :status,
                             :requestedAt, :createdAt, :updatedAt)
                        """)
                .param("id", id)
                .param("afterSaleId", id + 10_000)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("outRefundNo", outRefundNo)
                .param("refundId", refundId)
                .param("amountCent", amountCent)
                .param("status", status)
                .param("requestedAt", requestedAt)
                .param("createdAt", requestedAt)
                .param("updatedAt", requestedAt)
                .update();
    }

    private TradeBillRow paymentRow(
            long rowNo,
            String transactionId,
            String outTradeNo,
            long amountCent,
            String status
    ) {
        return new TradeBillRow(
                rowNo, TradeBillEntryType.PAYMENT, transactionId, outTradeNo, "", "",
                BUSINESS_TIME, amountCent, "CNY", status, digest(rowNo));
    }

    private TradeBillRow refundRow(
            long rowNo,
            String transactionId,
            String outTradeNo,
            String refundId,
            String outRefundNo,
            long amountCent,
            String status
    ) {
        return new TradeBillRow(
                rowNo, TradeBillEntryType.REFUND, transactionId, outTradeNo, refundId,
                outRefundNo, BUSINESS_TIME, amountCent, "CNY", status, digest(rowNo));
    }

    private String digest(long rowNo) {
        return String.format("%064x", rowNo);
    }
}
