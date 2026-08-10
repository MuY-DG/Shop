package org.muybaby.shopserver.finance.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinanceReconciliationSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void migrationCreatesEvidenceTablesConstraintsAndNullableGlobalAuditAnchor() {
        assertThat(jdbcClient.sql("""
                        select count(*) from information_schema.tables
                        where lower(table_name) in (
                            'finance_reconciliation_batch', 'wechat_trade_bill_entry',
                            'finance_reconciliation_difference',
                            'finance_reconciliation_resolution_audit'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(4);

        assertThat(jdbcClient.sql("""
                        select count(*) from information_schema.table_constraints
                        where lower(constraint_name) in (
                            'chk_finance_reconciliation_batch_bill_type',
                            'chk_finance_reconciliation_batch_counts',
                            'chk_finance_reconciliation_batch_claim',
                            'chk_wechat_trade_bill_entry_row_no',
                            'chk_finance_reconciliation_difference_candidate',
                            'uk_finance_reconciliation_difference'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(6);

        assertThat(jdbcClient.sql("""
                        select is_nullable from information_schema.columns
                        where lower(table_name) = 'finance_reconciliation_resolution_audit'
                          and lower(column_name) = 'batch_id'
                        """)
                .query(String.class)
                .single()).isEqualTo("YES");

        assertThat(jdbcClient.sql("""
                        select count(*) from information_schema.indexes
                        where lower(index_name) in (
                            'idx_refund_order_reconciliation_requested',
                            'idx_refund_order_reconciliation_payment',
                            'idx_refund_order_reconciliation_refund_id'
                        )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(3);
    }

    @Test
    void migrationSeedsFinanceMenuAndFivePermissionsOnlyForSuper() {
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_menu
                        where (id = 920 and path = '/finance' and component = '/index/index')
                           or (id = 921 and parent_id = 920 and path = 'reconciliation'
                               and component = '/finance/reconciliation/index')
                        """)
                .query(Integer.class)
                .single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_permission.permission_id between 21001 and 21005
                          and role_item.code = 'R_SUPER'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(5);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_permission.permission_id between 21001 and 21005
                          and role_item.code <> 'R_SUPER'
                        """)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void oneMerchantDateAndAllBillTypeCreatesOnlyOneBatch() {
        insertBatch("mch-schema", LocalDate.of(2026, 8, 1));

        assertThatThrownBy(() -> insertBatch("mch-schema", LocalDate.of(2026, 8, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sourceCandidateConstraintRejectsObjectMetadataWithoutSize() {
        insertBatch("mch-candidate-check", LocalDate.of(2026, 8, 1));
        long batchId = jdbcClient.sql("""
                        select id from finance_reconciliation_batch
                        where mch_id = 'mch-candidate-check'
                        """)
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into finance_reconciliation_difference
                            (batch_id, diff_key, difference_type, severity, status,
                             provider_evidence, local_evidence,
                             candidate_content_sha256, candidate_storage_provider,
                             candidate_storage_container, candidate_object_key,
                             candidate_size_bytes)
                        values
                            (:batchId, :diffKey, 'SOURCE_CHANGED', 'CRITICAL', 'OPEN',
                             '{}', '{}', :hash, 'TENCENT_COS', 'finance-bucket',
                             'private/finance/candidate.csv', null)
                        """)
                .param("batchId", batchId)
                .param("diffKey", "c".repeat(64))
                .param("hash", "d".repeat(64))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertBatch(String mchId, LocalDate billDate) {
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (mch_id, bill_date, bill_type, status, phase, requested_at,
                             created_at, updated_at)
                        values
                            (:mchId, :billDate, 'TRADE_ALL', 'PENDING', 'QUEUED', :now, :now, :now)
                        """)
                .param("mchId", mchId)
                .param("billDate", billDate)
                .param("now", LocalDateTime.of(2026, 8, 2, 10, 0))
                .update();
    }
}
