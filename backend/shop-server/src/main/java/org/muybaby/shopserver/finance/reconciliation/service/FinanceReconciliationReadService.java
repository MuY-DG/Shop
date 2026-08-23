package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchDetailResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationEntryQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationResolutionAuditResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminTradeBillEntryResponse;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class FinanceReconciliationReadService {

    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);

    private final JdbcClient jdbcClient;

    public FinanceReconciliationReadService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<AdminReconciliationBatchResponse> batches(AdminReconciliationBatchQuery query) {
        long current = query.pageCurrent();
        long size = query.pageSize();
        long offset = safeOffset(current, size);
        LocalDate from = query.billDateFrom() == null ? MIN_DATE : query.billDateFrom();
        LocalDate to = query.billDateTo() == null ? MAX_DATE : query.billDateTo();
        String mchId = normalize(query.mchId());
        String status = normalizeEnum(query.status());
        validateDateRange(from, to);
        String where = """
                where bill_date >= :fromDate and bill_date <= :toDate
                  and (:mchId = '' or mch_id = :mchId)
                  and (:status = '' or status = :status)
                """;
        long total = jdbcClient.sql("select count(*) from finance_reconciliation_batch " + where)
                .param("fromDate", from)
                .param("toDate", to)
                .param("mchId", mchId)
                .param("status", status)
                .query(Long.class)
                .single();
        List<AdminReconciliationBatchResponse> records = jdbcClient.sql("""
                        select * from finance_reconciliation_batch
                        """ + where + " order by bill_date desc, id desc limit :size offset :offset")
                .param("fromDate", from)
                .param("toDate", to)
                .param("mchId", mchId)
                .param("status", status)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapBatch)
                .list();
        return PageResult.of(records, total, current, size);
    }

    public AdminReconciliationBatchDetailResponse batchDetail(long batchId) {
        return jdbcClient.sql("select * from finance_reconciliation_batch where id = :batchId")
                .param("batchId", batchId)
                .query(this::mapBatchDetail)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public PageResult<AdminTradeBillEntryResponse> entries(
            long batchId,
            AdminReconciliationEntryQuery query
    ) {
        requireBatch(batchId);
        long current = query.pageCurrent();
        long size = query.pageSize();
        long offset = safeOffset(current, size);
        String type = normalizeEnum(query.entryType());
        String pattern = likePattern(query.keyword());
        String where = """
                where batch_id = :batchId
                  and (:entryType = '' or entry_type = :entryType)
                  and (:keyword = '' or transaction_id like :pattern escape '!'
                       or out_trade_no like :pattern escape '!'
                       or refund_id like :pattern escape '!'
                       or out_refund_no like :pattern escape '!')
                """;
        long total = jdbcClient.sql("select count(*) from wechat_trade_bill_entry " + where)
                .param("batchId", batchId)
                .param("entryType", type)
                .param("keyword", normalize(query.keyword()))
                .param("pattern", pattern)
                .query(Long.class)
                .single();
        List<AdminTradeBillEntryResponse> records = jdbcClient.sql("""
                        select * from wechat_trade_bill_entry
                        """ + where + " order by row_no, id limit :size offset :offset")
                .param("batchId", batchId)
                .param("entryType", type)
                .param("keyword", normalize(query.keyword()))
                .param("pattern", pattern)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapEntry)
                .list();
        return PageResult.of(records, total, current, size);
    }

    public PageResult<AdminReconciliationDifferenceResponse> differences(
            long batchId,
            AdminReconciliationDifferenceQuery query
    ) {
        requireBatch(batchId);
        long current = query.pageCurrent();
        long size = query.pageSize();
        long offset = safeOffset(current, size);
        String status = normalizeEnum(query.status());
        String type = normalizeEnum(query.type());
        String keyword = normalize(query.keyword());
        String pattern = likePattern(keyword);
        String where = """
                where batch_id = :batchId
                  and (:status = '' or status = :status)
                  and (:differenceType = '' or difference_type = :differenceType)
                  and (:keyword = '' or transaction_id like :pattern escape '!'
                       or out_trade_no like :pattern escape '!'
                       or refund_id like :pattern escape '!'
                       or out_refund_no like :pattern escape '!')
                """;
        long total = jdbcClient.sql("select count(*) from finance_reconciliation_difference " + where)
                .param("batchId", batchId)
                .param("status", status)
                .param("differenceType", type)
                .param("keyword", keyword)
                .param("pattern", pattern)
                .query(Long.class)
                .single();
        List<AdminReconciliationDifferenceResponse> records = jdbcClient.sql("""
                        select * from finance_reconciliation_difference
                        """ + where + " order by id desc limit :size offset :offset")
                .param("batchId", batchId)
                .param("status", status)
                .param("differenceType", type)
                .param("keyword", keyword)
                .param("pattern", pattern)
                .param("size", size)
                .param("offset", offset)
                .query(this::mapDifference)
                .list();
        return PageResult.of(records, total, current, size);
    }

    public AdminReconciliationDifferenceResponse difference(long differenceId) {
        return jdbcClient.sql("select * from finance_reconciliation_difference where id = :differenceId")
                .param("differenceId", differenceId)
                .query(this::mapDifference)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public List<AdminReconciliationResolutionAuditResponse> audits(long differenceId) {
        difference(differenceId);
        return jdbcClient.sql("""
                        select * from finance_reconciliation_resolution_audit
                        where difference_id = :differenceId
                        order by created_at, id
                        """)
                .param("differenceId", differenceId)
                .query(this::mapAudit)
                .list();
    }

    public StoredBatchSource source(long batchId) {
        return jdbcClient.sql("""
                        select id, bill_date, mch_id, storage_provider, storage_container,
                               storage_region, object_key, content_type, source_size_bytes,
                               content_sha256
                        from finance_reconciliation_batch
                        where id = :batchId and provider_hash_verified = true and object_key <> ''
                        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> new StoredBatchSource(
                        rs.getLong("id"),
                        rs.getDate("bill_date").toLocalDate(),
                        rs.getString("mch_id"),
                        new StorageObjectLocation(
                                StorageProviderKind.valueOf(rs.getString("storage_provider")),
                                rs.getString("storage_container"),
                                rs.getString("storage_region"),
                                rs.getString("object_key")
                        ),
                        rs.getString("content_type"),
                        rs.getLong("source_size_bytes"),
                        rs.getString("content_sha256")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE));
    }

    public StoredCandidateSource candidateSource(long differenceId) {
        return jdbcClient.sql("""
                        select difference_entry.id, difference_entry.batch_id,
                               batch.bill_date, batch.mch_id,
                               difference_entry.candidate_storage_provider,
                               difference_entry.candidate_storage_container,
                               difference_entry.candidate_storage_region,
                               difference_entry.candidate_object_key,
                               difference_entry.candidate_size_bytes,
                               difference_entry.candidate_content_sha256
                        from finance_reconciliation_difference difference_entry
                        join finance_reconciliation_batch batch
                          on batch.id = difference_entry.batch_id
                        where difference_entry.id = :differenceId
                          and difference_entry.difference_type = 'SOURCE_CHANGED'
                          and difference_entry.candidate_object_key <> ''
                          and difference_entry.candidate_content_sha256 <> ''
                          and difference_entry.candidate_size_bytes is not null
                        """)
                .param("differenceId", differenceId)
                .query((rs, rowNum) -> new StoredCandidateSource(
                        rs.getLong("id"),
                        rs.getLong("batch_id"),
                        rs.getDate("bill_date").toLocalDate(),
                        rs.getString("mch_id"),
                        new StorageObjectLocation(
                                StorageProviderKind.valueOf(
                                        rs.getString("candidate_storage_provider")),
                                rs.getString("candidate_storage_container"),
                                rs.getString("candidate_storage_region"),
                                rs.getString("candidate_object_key")
                        ),
                        rs.getLong("candidate_size_bytes"),
                        rs.getString("candidate_content_sha256")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE));
    }

    private AdminReconciliationBatchResponse mapBatch(ResultSet rs, int rowNum) throws SQLException {
        return new AdminReconciliationBatchResponse(
                rs.getLong("id"),
                rs.getDate("bill_date").toLocalDate(),
                rs.getString("mch_id"),
                rs.getString("status"),
                rs.getString("phase"),
                rs.getBoolean("provider_hash_verified"),
                rs.getString("content_sha256"),
                rs.getBoolean("provider_hash_verified") && !rs.getString("object_key").isBlank(),
                rs.getLong("source_size_bytes"),
                rs.getLong("total_rows"),
                rs.getLong("payment_rows"),
                rs.getLong("refund_rows"),
                rs.getLong("difference_count"),
                rs.getLong("open_difference_count"),
                rs.getInt("attempt_count"),
                rs.getObject("next_attempt_at", java.time.LocalDateTime.class),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                rs.getObject("requested_by", Long.class),
                rs.getObject("requested_at", java.time.LocalDateTime.class),
                rs.getObject("started_at", java.time.LocalDateTime.class),
                rs.getObject("completed_at", java.time.LocalDateTime.class),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("updated_at", java.time.LocalDateTime.class),
                rs.getLong("version")
        );
    }

    private AdminReconciliationBatchDetailResponse mapBatchDetail(ResultSet rs, int rowNum)
            throws SQLException {
        AdminReconciliationBatchResponse batch = mapBatch(rs, rowNum);
        return new AdminReconciliationBatchDetailResponse(
                batch.id(), batch.billDate(), batch.mchId(), batch.status(), batch.phase(),
                batch.providerHashVerified(), batch.contentSha256(), batch.sourceAvailable(),
                batch.sourceSizeBytes(), batch.totalRows(), batch.paymentRows(), batch.refundRows(),
                batch.differenceCount(), batch.openDifferenceCount(), batch.attemptCount(),
                batch.nextAttemptAt(), batch.lastErrorCode(), batch.lastErrorMessage(),
                batch.requestedBy(), batch.requestedAt(), batch.startedAt(), batch.completedAt(),
                batch.createdAt(), batch.updatedAt(), batch.version(),
                rs.getLong("channel_payment_amount_cent"),
                rs.getLong("channel_refund_amount_cent"),
                rs.getLong("local_payment_amount_cent"),
                rs.getLong("local_refund_amount_cent")
        );
    }

    private AdminTradeBillEntryResponse mapEntry(ResultSet rs, int rowNum) throws SQLException {
        return new AdminTradeBillEntryResponse(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getLong("row_no"),
                rs.getString("entry_type"),
                rs.getString("transaction_id"),
                rs.getString("out_trade_no"),
                rs.getString("refund_id"),
                rs.getString("out_refund_no"),
                rs.getObject("occurred_at", java.time.LocalDateTime.class),
                rs.getLong("amount_cent"),
                rs.getString("currency"),
                rs.getString("channel_status"),
                rs.getString("row_digest"),
                rs.getObject("created_at", java.time.LocalDateTime.class)
        );
    }

    private AdminReconciliationDifferenceResponse mapDifference(ResultSet rs, int rowNum)
            throws SQLException {
        return new AdminReconciliationDifferenceResponse(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("diff_key"),
                rs.getString("difference_type"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("transaction_id"),
                rs.getString("out_trade_no"),
                rs.getString("refund_id"),
                rs.getString("out_refund_no"),
                rs.getObject("order_id", Long.class),
                rs.getObject("payment_order_id", Long.class),
                rs.getObject("refund_order_id", Long.class),
                rs.getObject("provider_amount_cent", Long.class),
                rs.getObject("local_amount_cent", Long.class),
                rs.getString("provider_status"),
                rs.getString("local_status"),
                rs.getLong("version"),
                rs.getString("resolution_code"),
                rs.getString("resolution_reason"),
                rs.getObject("resolved_by", Long.class),
                rs.getObject("resolved_at", java.time.LocalDateTime.class),
                rs.getObject("created_at", java.time.LocalDateTime.class),
                rs.getObject("updated_at", java.time.LocalDateTime.class),
                rs.getString("candidate_content_sha256"),
                rs.getObject("candidate_size_bytes", Long.class),
                !rs.getString("candidate_object_key").isBlank()
                        && !rs.getString("candidate_content_sha256").isBlank()
                        && rs.getObject("candidate_size_bytes") != null,
                rs.getBoolean("external_refund_applied")
        );
    }

    private AdminReconciliationResolutionAuditResponse mapAudit(ResultSet rs, int rowNum)
            throws SQLException {
        return new AdminReconciliationResolutionAuditResponse(
                rs.getLong("id"),
                rs.getObject("difference_id", Long.class),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("action"),
                rs.getString("resolution_code"),
                rs.getString("reason"),
                rs.getObject("operator_id", Long.class),
                rs.getObject("created_at", java.time.LocalDateTime.class)
        );
    }

    private void requireBatch(long batchId) {
        if (jdbcClient.sql("select count(*) from finance_reconciliation_batch where id = :batchId")
                .param("batchId", batchId)
                .query(Long.class)
                .single() != 1L) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeEnum(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    private String likePattern(String value) {
        String normalized = normalize(value)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + normalized + "%";
    }

    private long safeOffset(long current, long size) {
        try {
            return Math.multiplyExact(current - 1L, size);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    public record StoredBatchSource(
            long batchId,
            LocalDate billDate,
            String mchId,
            StorageObjectLocation location,
            String contentType,
            long sizeBytes,
            String contentSha256
    ) {
    }

    public record StoredCandidateSource(
            long differenceId,
            long batchId,
            LocalDate billDate,
            String mchId,
            StorageObjectLocation location,
            long sizeBytes,
            String contentSha256
    ) {
    }
}
