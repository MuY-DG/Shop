package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceSeverity;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeReconciliationProcessorDifferenceTest {

    @Autowired
    private TradeReconciliationProcessor processor;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void sourceChangedUpsertDoesNotAutoClearBusinessDifferences() {
        long batchId = insertBatch();
        DifferenceDraft payment = draft("1".repeat(64), ReconciliationDifferenceType.CHANNEL_ONLY,
                null, "provider-payment-v1");
        DifferenceDraft refund = draft("2".repeat(64), ReconciliationDifferenceType.LOCAL_ONLY,
                null, "provider-refund-v1");
        processor.applyDifferences(batchId, List.of(payment, refund));

        DifferenceDraft sourceChanged = draft(
                "3".repeat(64), ReconciliationDifferenceType.SOURCE_CHANGED,
                null, "oldHash=a;newHash=b");
        processor.reconcileDifferences(batchId, List.of(sourceChanged), false);

        assertThat(activeTypes(batchId)).containsExactlyInAnyOrder(
                "CHANNEL_ONLY", "LOCAL_ONLY", "SOURCE_CHANGED");
    }

    @Test
    void stableKeyRefreshesEvidenceAndLinksThenReopensResolvedDifference() {
        long batchId = insertBatch();
        long orderId = insertOrder();
        String key = "4".repeat(64);
        processor.applyDifferences(batchId, List.of(draft(
                key, ReconciliationDifferenceType.STATUS_MISMATCH, null, "evidence-v1")));

        processor.applyDifferences(batchId, List.of(draft(
                key, ReconciliationDifferenceType.STATUS_MISMATCH, orderId, "evidence-v2")));
        DifferenceState refreshed = difference(batchId, key);
        assertThat(refreshed.providerEvidence()).isEqualTo("evidence-v2");
        assertThat(refreshed.orderId()).isEqualTo(orderId);
        assertThat(refreshed.status()).isEqualTo("OPEN");

        jdbcClient.sql("""
                        update finance_reconciliation_difference
                        set status = 'RESOLVED', resolution_code = 'MANUAL',
                            resolution_reason = 'handled', resolved_at = current_timestamp
                        where batch_id = :batchId and diff_key = :diffKey
                        """)
                .param("batchId", batchId)
                .param("diffKey", key)
                .update();
        processor.applyDifferences(batchId, List.of(draft(
                key, ReconciliationDifferenceType.STATUS_MISMATCH, orderId, "evidence-v3")));

        DifferenceState reopened = difference(batchId, key);
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.providerEvidence()).isEqualTo("evidence-v3");
        assertThat(reopened.resolutionCode()).isEqualTo("");
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_resolution_audit
                        where batch_id = :batchId and action = 'REOPEN'
                        """)
                .param("batchId", batchId)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void rerunKeepsAnAppliedExternalRefundResolved() {
        long batchId = insertBatch();
        String key = "5".repeat(64);
        processor.applyDifferences(batchId, List.of(draft(
                key, ReconciliationDifferenceType.CHANNEL_ONLY, null, "provider-v1")));
        jdbcClient.sql("""
                        update finance_reconciliation_difference
                        set status = 'RESOLVED', external_refund_applied = true,
                            local_amount_cent = 100,
                            local_status = 'EXTERNAL_REFUND_RECORDED',
                            resolution_code = 'EXTERNAL_REFUND_RECORDED',
                            resolution_reason = 'verified', resolved_at = current_timestamp
                        where batch_id = :batchId and diff_key = :diffKey
                        """)
                .param("batchId", batchId)
                .param("diffKey", key)
                .update();

        processor.applyDifferences(batchId, List.of(draft(
                key, ReconciliationDifferenceType.CHANNEL_ONLY, null, "provider-v2")));

        assertThat(jdbcClient.sql("""
                        select concat(status, '|', local_status, '|', resolution_code)
                        from finance_reconciliation_difference
                        where batch_id = :batchId and diff_key = :diffKey
                        """)
                .param("batchId", batchId)
                .param("diffKey", key)
                .query(String.class)
                .single()).isEqualTo(
                        "RESOLVED|EXTERNAL_REFUND_RECORDED|EXTERNAL_REFUND_RECORDED");
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_resolution_audit
                        where batch_id = :batchId and action = 'REOPEN'
                        """)
                .param("batchId", batchId)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void newDifferencesArePublishedInBoundedSqlBatches() {
        long batchId = insertBatch();
        List<DifferenceDraft> drafts = LongStream.range(1L, 1_206L)
                .mapToObj(index -> draft(
                        "%064x".formatted(index),
                        ReconciliationDifferenceType.CHANNEL_ONLY,
                        null,
                        "evidence-" + index))
                .toList();

        processor.applyDifferences(batchId, drafts);

        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_difference
                        where batch_id = :batchId
                        """)
                .param("batchId", batchId)
                .query(Integer.class)
                .single()).isEqualTo(1_205);
    }

    private long insertBatch() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 10, 0);
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (mch_id, bill_date, bill_type, status, phase,
                             requested_at, created_at, updated_at)
                        values
                            ('mch-processor', date '2026-08-01', 'TRADE_ALL',
                             'DIFFERENCES', 'COMPLETE', :now, :now, :now)
                        """)
                .param("now", now)
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }

    private long insertOrder() {
        long orderId = 9_350_011L;
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             created_at, updated_at)
                        values
                            (:id, 'ORDER-PROCESSOR-DIFF', 1, 'PAID', 'CART',
                             'processor-diff-order',
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             :now, :now)
                        """)
                .param("id", orderId)
                .param("now", LocalDateTime.of(2026, 8, 1, 1, 0))
                .update();
        return orderId;
    }

    private DifferenceDraft draft(
            String key,
            ReconciliationDifferenceType type,
            Long orderId,
            String evidence
    ) {
        return new DifferenceDraft(
                key,
                type,
                ReconciliationDifferenceSeverity.CRITICAL,
                "transaction", "trade", "refund", "out-refund",
                orderId, null, null, 100L, 90L,
                "SUCCESS", "PAID", evidence, "{}"
        );
    }

    private List<String> activeTypes(long batchId) {
        return jdbcClient.sql("""
                        select difference_type from finance_reconciliation_difference
                        where batch_id = :batchId and status in ('OPEN', 'INVESTIGATING')
                        order by difference_type
                        """)
                .param("batchId", batchId)
                .query(String.class)
                .list();
    }

    private DifferenceState difference(long batchId, String key) {
        return jdbcClient.sql("""
                        select status, provider_evidence, order_id, resolution_code
                        from finance_reconciliation_difference
                        where batch_id = :batchId and diff_key = :diffKey
                        """)
                .param("batchId", batchId)
                .param("diffKey", key)
                .query((rs, rowNum) -> new DifferenceState(
                        rs.getString("status"),
                        rs.getString("provider_evidence"),
                        rs.getObject("order_id", Long.class),
                        rs.getString("resolution_code")
                ))
                .single();
    }

    private record DifferenceState(
            String status,
            String providerEvidence,
            Long orderId,
            String resolutionCode
    ) {
    }
}
