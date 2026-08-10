package org.muybaby.shopserver.finance.reconciliation.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceResponse;
import org.muybaby.shopserver.finance.reconciliation.service.FinanceReconciliationExportService.ExportFilter;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationInvestigateRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationResolveRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationRetryRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationRunRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class FinanceReconciliationCommandService {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final ReconciliationCredentialCatalog credentialCatalog;
    private final FinanceReconciliationReadService readService;
    private final FinanceReconciliationProperties properties;
    private final Clock clock;

    public FinanceReconciliationCommandService(
            JdbcClient jdbcClient,
            TransactionTemplate transactionTemplate,
            ReconciliationCredentialCatalog credentialCatalog,
            FinanceReconciliationReadService readService,
            FinanceReconciliationProperties properties,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
        this.credentialCatalog = credentialCatalog;
        this.readService = readService;
        this.properties = properties;
        this.clock = clock;
    }

    public List<AdminReconciliationBatchResponse> requestRuns(
            AdminReconciliationRunRequest request,
            Long operatorId
    ) {
        requireWorkerEnabled();
        validateBillDate(request.billDate());
        List<ReconciliationCredential> credentials = credentialCatalog.available(request.billDate());
        String requestedMchId = normalize(request.mchId());
        if (!requestedMchId.isEmpty()) {
            credentials = credentials.stream()
                    .filter(candidate -> requestedMchId.equals(candidate.mchId()))
                    .toList();
        }
        if (credentials.isEmpty()) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_UNAVAILABLE);
        }
        List<AdminReconciliationBatchResponse> responses = new ArrayList<>();
        for (ReconciliationCredential credential : credentials) {
            Long batchId = createOrGetBatch(request.billDate(), credential, operatorId, "RUN");
            responses.add(readService.batches(new org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchQuery(
                            1L, 1L, request.billDate(), request.billDate(), credential.mchId(), ""))
                    .records().stream()
                    .filter(batch -> batch.id().equals(batchId))
                    .findFirst()
                    .orElseGet(() -> batchSummary(batchId)));
        }
        return List.copyOf(responses);
    }

    public List<AdminReconciliationBatchResponse> requestDaily(LocalDate billDate) {
        if (!properties.workerEnabled()) {
            return List.of();
        }
        validateBillDate(billDate);
        List<AdminReconciliationBatchResponse> responses = new ArrayList<>();
        for (ReconciliationCredential credential : credentialCatalog.available(billDate)) {
            Long batchId = createOrGetBatch(billDate, credential, null, "RUN");
            responses.add(batchSummary(batchId));
        }
        return List.copyOf(responses);
    }

    public AdminReconciliationBatchResponse retry(
            long batchId,
            AdminReconciliationRetryRequest request,
            Long operatorId
    ) {
        requireWorkerEnabled();
        Boolean updated = transactionTemplate.execute(status -> {
            BatchState batch = lockBatch(batchId);
            if (batch.version() != request.version()
                    || "RUNNING".equals(batch.status()) || "PENDING".equals(batch.status())) {
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
            }
            LocalDateTime now = now();
            int rows = jdbcClient.sql("""
                            update finance_reconciliation_batch
                            set status = 'PENDING', phase = 'QUEUED', claim_token = null,
                                claimed_at = null, next_attempt_at = null,
                                attempt_count = 0,
                                last_error_code = '', last_error_message = '',
                                requested_by = :operatorId, requested_at = :requestedAt,
                                completed_at = null, version = version + 1, updated_at = :updatedAt
                            where id = :batchId and version = :version
                            """)
                    .param("operatorId", operatorId)
                    .param("requestedAt", now)
                    .param("updatedAt", now)
                    .param("batchId", batchId)
                    .param("version", batch.version())
                    .update();
            if (rows != 1) {
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
            }
            audit(batchId, null, batch.status(), "PENDING", "RETRY", "", request.reason(), operatorId, "");
            return true;
        });
        if (!Boolean.TRUE.equals(updated)) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
        }
        return batchSummary(batchId);
    }

    public AdminReconciliationDifferenceResponse investigate(
            long differenceId,
            AdminReconciliationInvestigateRequest request,
            Long operatorId
    ) {
        updateDifference(
                differenceId,
                request.version(),
                "INVESTIGATING",
                "INVESTIGATE",
                "",
                request.reason(),
                operatorId
        );
        return readService.difference(differenceId);
    }

    public AdminReconciliationDifferenceResponse resolve(
            long differenceId,
            AdminReconciliationResolveRequest request,
            Long operatorId
    ) {
        updateDifference(
                differenceId,
                request.version(),
                "RESOLVED",
                "RESOLVE",
                request.resolutionCode(),
                request.reason(),
                operatorId
        );
        return readService.difference(differenceId);
    }

    public void auditSourceDownload(long batchId, Long operatorId, long byteCount) {
        transactionTemplate.executeWithoutResult(status -> {
            requireBatch(batchId);
            audit(batchId, null, "", "", "SOURCE_DOWNLOAD", "", "下载原始微信交易账单",
                    operatorId, "source=verified;bytes=" + byteCount);
        });
    }

    public void auditCandidateSourceDownload(
            long batchId,
            long differenceId,
            Long operatorId,
            long byteCount
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            long matching = jdbcClient.sql("""
                            select count(*) from finance_reconciliation_difference
                            where id = :differenceId and batch_id = :batchId
                              and difference_type = 'SOURCE_CHANGED'
                            """)
                    .param("differenceId", differenceId)
                    .param("batchId", batchId)
                    .query(Long.class)
                    .single();
            if (matching != 1L) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            audit(batchId, differenceId, "", "", "SOURCE_DOWNLOAD", "",
                    "下载来源变更候选账单", operatorId,
                    "source=candidate;bytes=" + byteCount);
        });
    }

    public void auditExport(
            Long operatorId,
            ExportFilter filter,
            long recordCount,
            long byteCount
    ) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        insert into finance_reconciliation_resolution_audit
                            (batch_id, difference_id, from_status, to_status, action,
                             resolution_code, reason, metadata, operator_id, created_at)
                        values
                            (null, null, '', '', 'EXPORT', '', :reason, :metadata,
                             :operatorId, :createdAt)
                        """)
                .param("reason", "导出微信交易对账明细")
                .param("metadata", "from=" + filter.from() + ";to=" + filter.to()
                        + ";mchId=" + filter.mchId()
                        + ";batchStatus=" + filter.batchStatus()
                        + ";differenceStatus=" + filter.differenceStatus()
                        + ";differenceType=" + filter.differenceType()
                        + ";records=" + recordCount + ";bytes=" + byteCount)
                .param("operatorId", operatorId)
                .param("createdAt", now())
                .update());
    }

    private Long createOrGetBatch(
            LocalDate billDate,
            ReconciliationCredential credential,
            Long operatorId,
            String action
    ) {
        try {
            return transactionTemplate.execute(status -> insertBatch(
                    billDate, credential, operatorId, action));
        } catch (DuplicateKeyException ignored) {
            Long batchId = existingBatchId(credential.mchId(), billDate);
            transactionTemplate.executeWithoutResult(status -> audit(
                    batchId, null, "", "", action, "", "请求微信交易账单对账",
                    operatorId, "idempotent=true"));
            return batchId;
        }
    }

    private Long insertBatch(
            LocalDate billDate,
            ReconciliationCredential credential,
            Long operatorId,
            String action
    ) {
        LocalDateTime now = now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (mch_id, bill_date, bill_type, credential_config_id,
                             credential_fingerprint, status, phase, requested_by, requested_at,
                             created_at, updated_at)
                        values
                            (:mchId, :billDate, 'TRADE_ALL', :configId,
                             :fingerprint, 'PENDING', 'QUEUED', :requestedBy, :requestedAt,
                             :createdAt, :updatedAt)
                        """)
                .param("mchId", credential.mchId())
                .param("billDate", billDate)
                .param("configId", credential.configId())
                .param("fingerprint", credential.fingerprint())
                .param("requestedBy", operatorId)
                .param("requestedAt", now)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update(keyHolder, "id");
        if (inserted != 1 || keyHolder.getKey() == null) {
            throw new IllegalStateException("Finance reconciliation batch was not inserted");
        }
        long batchId = keyHolder.getKey().longValue();
        audit(batchId, null, "", "PENDING", action, "", "请求微信交易账单对账", operatorId, "");
        return batchId;
    }

    private void updateDifference(
            long differenceId,
            long expectedVersion,
            String targetStatus,
            String action,
            String resolutionCode,
            String reason,
            Long operatorId
    ) {
        transactionTemplate.executeWithoutResult(transaction -> {
            long batchId = jdbcClient.sql("""
                            select batch_id from finance_reconciliation_difference
                            where id = :differenceId
                            """)
                    .param("differenceId", differenceId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            jdbcClient.sql("""
                            select id from finance_reconciliation_batch
                            where id = :batchId
                            for update
                            """)
                    .param("batchId", batchId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            DifferenceState difference = jdbcClient.sql("""
                            select id, batch_id, status, version
                            from finance_reconciliation_difference
                            where id = :differenceId and batch_id = :batchId
                            for update
                            """)
                    .param("differenceId", differenceId)
                    .param("batchId", batchId)
                    .query((rs, rowNum) -> new DifferenceState(
                            rs.getLong("id"),
                            rs.getLong("batch_id"),
                            rs.getString("status"),
                            rs.getLong("version")
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (difference.version() != expectedVersion
                    || !("OPEN".equals(difference.status())
                    || "INVESTIGATING".equals(difference.status()))) {
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
            }
            if ("INVESTIGATING".equals(targetStatus)
                    && "INVESTIGATING".equals(difference.status())) {
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
            }
            LocalDateTime now = now();
            int rows = jdbcClient.sql("""
                            update finance_reconciliation_difference
                            set status = :targetStatus,
                                resolution_code = :resolutionCode,
                                resolution_reason = :reason,
                                resolved_by = case when :resolved then :operatorId else null end,
                                resolved_at = case when :resolved then :resolvedAt else null end,
                                version = version + 1, updated_at = :updatedAt
                            where id = :differenceId and version = :version
                            """)
                    .param("targetStatus", targetStatus)
                    .param("resolutionCode", resolutionCode)
                    .param("reason", reason)
                    .param("resolved", "RESOLVED".equals(targetStatus))
                    .param("operatorId", operatorId)
                    .param("resolvedAt", now)
                    .param("updatedAt", now)
                    .param("differenceId", differenceId)
                    .param("version", expectedVersion)
                    .update();
            if (rows != 1) {
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT);
            }
            jdbcClient.sql("""
                            update finance_reconciliation_batch
                            set open_difference_count = (
                                    select count(*) from finance_reconciliation_difference difference_entry
                                    where difference_entry.batch_id = :batchId
                                      and difference_entry.status in ('OPEN', 'INVESTIGATING')
                                ),
                                version = version + 1, updated_at = :updatedAt
                            where id = :batchId
                            """)
                    .param("batchId", difference.batchId())
                    .param("updatedAt", now)
                    .update();
            audit(
                    difference.batchId(),
                    difference.id(),
                    difference.status(),
                    targetStatus,
                    action,
                    resolutionCode,
                    reason,
                    operatorId,
                    ""
            );
        });
    }

    private void audit(
            long batchId,
            Long differenceId,
            String fromStatus,
            String toStatus,
            String action,
            String resolutionCode,
            String reason,
            Long operatorId,
            String metadata
    ) {
        jdbcClient.sql("""
                        insert into finance_reconciliation_resolution_audit
                            (batch_id, difference_id, from_status, to_status, action,
                             resolution_code, reason, metadata, operator_id, created_at)
                        values
                            (:batchId, :differenceId, :fromStatus, :toStatus, :action,
                             :resolutionCode, :reason, :metadata, :operatorId, :createdAt)
                        """)
                .param("batchId", batchId)
                .param("differenceId", differenceId)
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("action", action)
                .param("resolutionCode", normalize(resolutionCode).toUpperCase(Locale.ROOT))
                .param("reason", reason)
                .param("metadata", metadata)
                .param("operatorId", operatorId)
                .param("createdAt", now())
                .update();
    }

    private BatchState lockBatch(long batchId) {
        return jdbcClient.sql("""
                        select id, status, version
                        from finance_reconciliation_batch
                        where id = :batchId
                        for update
                        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> new BatchState(
                        rs.getLong("id"), rs.getString("status"), rs.getLong("version")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireBatch(long batchId) {
        if (jdbcClient.sql("select count(*) from finance_reconciliation_batch where id = :batchId")
                .param("batchId", batchId)
                .query(Long.class)
                .single() != 1L) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private Long existingBatchId(String mchId, LocalDate billDate) {
        return jdbcClient.sql("""
                        select id from finance_reconciliation_batch
                        where mch_id = :mchId and bill_date = :billDate and bill_type = 'TRADE_ALL'
                        """)
                .param("mchId", mchId)
                .param("billDate", billDate)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.FINANCE_RECONCILIATION_CONFLICT));
    }

    private AdminReconciliationBatchResponse batchSummary(long batchId) {
        AdminReconciliationBatchQueryById query = new AdminReconciliationBatchQueryById(batchId);
        return query.read(jdbcClient);
    }

    private void requireWorkerEnabled() {
        if (!properties.workerEnabled()) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_DISABLED);
        }
    }

    private void validateBillDate(LocalDate billDate) {
        LocalDate today = LocalDate.now(clock.withZone(TimePolicy.BUSINESS_ZONE));
        if (billDate == null || !billDate.isBefore(today)
                || billDate.isBefore(today.minusDays(properties.lookbackDays()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(TimePolicy.UTC));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record BatchState(long id, String status, long version) {
    }

    private record DifferenceState(long id, long batchId, String status, long version) {
    }

    private record AdminReconciliationBatchQueryById(long batchId) {
        AdminReconciliationBatchResponse read(JdbcClient jdbcClient) {
            return jdbcClient.sql("select * from finance_reconciliation_batch where id = :batchId")
                    .param("batchId", batchId)
                    .query((rs, rowNum) -> new AdminReconciliationBatchResponse(
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
                            rs.getObject("next_attempt_at", LocalDateTime.class),
                            rs.getString("last_error_code"),
                            rs.getString("last_error_message"),
                            rs.getObject("requested_by", Long.class),
                            rs.getObject("requested_at", LocalDateTime.class),
                            rs.getObject("started_at", LocalDateTime.class),
                            rs.getObject("completed_at", LocalDateTime.class),
                            rs.getObject("created_at", LocalDateTime.class),
                            rs.getObject("updated_at", LocalDateTime.class),
                            rs.getLong("version")
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        }
    }
}
