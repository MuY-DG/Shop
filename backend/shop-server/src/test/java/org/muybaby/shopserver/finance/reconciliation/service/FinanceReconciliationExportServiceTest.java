package org.muybaby.shopserver.finance.reconciliation.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationExportQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinanceReconciliationExportServiceTest {

    private static final long LARGE_BATCH_ID = 9_007_199_254_740_993L;
    private static final long LARGE_DIFFERENCE_ID = 9_007_199_254_740_994L;

    @Autowired
    private FinanceReconciliationExportService exportService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private FinanceReconciliationCommandService commandService;

    @Test
    void exportsBomRfc4180FormulaSafeCsvAndTextualIdentifiers() {
        seedDifference();

        FinanceReconciliationExportService.ExportedCsv exported = exportService.export(
                new AdminReconciliationExportQuery(
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 1),
                        "0012345678901234567890",
                        "DIFFERENCES",
                        "OPEN",
                        "CHANNEL_ONLY"));

        byte[] bytes = exported.bytes();
        assertThat(bytes).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(exported.recordCount()).isOne();
        assertThat(csv).contains("\"'0012345678901234567890\"");
        assertThat(csv).contains("\"'9007199254740993\"");
        assertThat(csv).contains("\"'9007199254740994\"");
        assertThat(csv).contains("\"'00123456789012345678\"");
        assertThat(csv).contains("\"'  =HYPERLINK(\"\"https://invalid.test\"\")\"");
        assertThat(csv).contains("\"'  @SUM(A1:A2), \"\"quoted\"\"\r\nline\"");
        assertThat(csv).contains("\"123\"");
    }

    @Test
    void rejectsInvertedOrOverThirtyOneDayRangesBeforeExporting() {
        assertThatThrownBy(() -> exportService.export(new AdminReconciliationExportQuery(
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1), "", "", "", "")))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> exportService.export(new AdminReconciliationExportQuery(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), "", "", "", "")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void zeroRowExportStillWritesGlobalAuditWithEveryNormalizedFilter() {
        FinanceReconciliationExportService.ExportFilter filter =
                new FinanceReconciliationExportService.ExportFilter(
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 2),
                        "mch-audit",
                        "BALANCED",
                        "RESOLVED",
                        "STATUS_MISMATCH");

        commandService.auditExport(1L, filter, 0L, 3L);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from finance_reconciliation_resolution_audit
                        where action = 'EXPORT' and batch_id is null and operator_id = 1
                        """)
                .query(Integer.class)
                .single()).isOne();
        String metadata = jdbcClient.sql("""
                        select metadata from finance_reconciliation_resolution_audit
                        where action = 'EXPORT' and batch_id is null and operator_id = 1
                        order by id desc limit 1
                        """)
                .query(String.class)
                .single();
        assertThat(metadata)
                .contains("from=2026-08-01", "to=2026-08-02", "mchId=mch-audit",
                        "batchStatus=BALANCED", "differenceStatus=RESOLVED",
                        "differenceType=STATUS_MISMATCH", "records=0", "bytes=3");
    }

    private void seedDifference() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 10, 0);
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (id, mch_id, bill_date, bill_type, status, phase,
                             requested_at, created_at, updated_at)
                        values
                            (:id, :mchId, date '2026-08-01', 'TRADE_ALL', 'DIFFERENCES',
                             'COMPLETE', :now, :now, :now)
                        """)
                .param("id", LARGE_BATCH_ID)
                .param("mchId", "0012345678901234567890")
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into finance_reconciliation_difference
                            (id, batch_id, diff_key, difference_type, severity, status,
                             transaction_id, out_trade_no, refund_id, out_refund_no,
                             provider_amount_cent, local_amount_cent, provider_status, local_status,
                             provider_evidence, local_evidence, resolution_reason,
                             created_at, updated_at)
                        values
                            (:id, :batchId, :diffKey, 'CHANNEL_ONLY', 'CRITICAL', 'OPEN',
                             '00123456789012345678', '  =HYPERLINK("https://invalid.test")',
                             '', '', 123, null, 'SUCCESS', '', '{}', '{}',
                             :reason, :now, :now)
                        """)
                .param("id", LARGE_DIFFERENCE_ID)
                .param("batchId", LARGE_BATCH_ID)
                .param("diffKey", "a".repeat(64))
                .param("reason", "  @SUM(A1:A2), \"quoted\"\r\nline")
                .param("now", now)
                .update();
    }
}
