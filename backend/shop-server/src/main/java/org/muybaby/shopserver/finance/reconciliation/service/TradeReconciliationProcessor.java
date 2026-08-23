package org.muybaby.shopserver.finance.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.exception.HttpException;
import com.wechat.pay.java.core.exception.ServiceException;
import org.muybaby.shopserver.common.time.TimePolicy;
import org.muybaby.shopserver.finance.reconciliation.FinanceReconciliationProperties;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceSeverity;
import org.muybaby.shopserver.finance.reconciliation.ReconciliationDifferenceType;
import org.muybaby.shopserver.finance.reconciliation.download.StagedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.download.TradeBillDownloadService;
import org.muybaby.shopserver.finance.reconciliation.parser.ParsedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.parser.TradeBillRow;
import org.muybaby.shopserver.finance.reconciliation.parser.WechatTradeBillParser;
import org.muybaby.shopserver.finance.reconciliation.storage.FinanceTradeBillStorage;
import org.muybaby.shopserver.finance.reconciliation.storage.StoredTradeBillSource;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TradeReconciliationProcessor {

    private static final Logger log = LoggerFactory.getLogger(TradeReconciliationProcessor.class);
    private static final int ENTRY_INSERT_CHUNK = 1_000;
    private static final int DIFFERENCE_INSERT_CHUNK = 500;

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactionTemplate;
    private final ReconciliationCredentialCatalog credentialCatalog;
    private final TradeBillDownloadService downloadService;
    private final WechatTradeBillParser parser;
    private final FinanceTradeBillStorage storage;
    private final TradeReconciliationMatcher matcher;
    private final FinanceReconciliationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TradeReconciliationProcessor(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedJdbc,
            TransactionTemplate transactionTemplate,
            ReconciliationCredentialCatalog credentialCatalog,
            TradeBillDownloadService downloadService,
            WechatTradeBillParser parser,
            FinanceTradeBillStorage storage,
            TradeReconciliationMatcher matcher,
            FinanceReconciliationProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.namedJdbc = namedJdbc;
        this.transactionTemplate = transactionTemplate;
        this.credentialCatalog = credentialCatalog;
        this.downloadService = downloadService;
        this.parser = parser;
        this.storage = storage;
        this.matcher = matcher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public boolean processNext() {
        ClaimedBatch batch = claimNext();
        if (batch == null) {
            return false;
        }
        process(batch);
        return true;
    }

    private void process(ClaimedBatch batch) {
        StoredTradeBillSource uploaded = null;
        boolean retainUploaded = false;
        StagedTradeBill staged = null;
        try {
            ReconciliationCredential credential = credentialCatalog.require(batch.mchId(), batch.billDate());
            updatePhase(batch, "DOWNLOAD", credential);
            staged = downloadService.download(credential.config(), batch.billDate());
            updatePhase(batch, "VERIFY", credential);
            updatePhase(batch, "PARSE", credential);
            ParsedTradeBill parsed = parser.parse(staged.path());
            updatePhase(batch, "STORE", credential);
            try {
                uploaded = storage.store(batch.mchId(), batch.billDate(), staged);
            } catch (RuntimeException ex) {
                throw new RetryableSourceStorageException(ex);
            }
            updatePhase(batch, "COMPARE", credential);
            TradeReconciliationResult comparison = matcher.compare(
                    batch.mchId(), batch.billDate(), parsed);
            StagedTradeBill downloaded = staged;
            StoredTradeBillSource stored = uploaded;
            CommitOutcome outcome = transactionTemplate.execute(status -> commit(
                    batch, credential, downloaded, stored, parsed, comparison));
            retainUploaded = outcome != null && outcome.retainUploaded();
            if (outcome != null && outcome.deleteAfterCommit() != null) {
                storage.deleteQuietly(outcome.deleteAfterCommit());
            }
        } catch (ServiceException ex) {
            if ("NO_STATEMENT_EXIST".equals(ex.getErrorCode())) {
                handleNoStatement(batch);
            } else {
                recordFailure(batch, providerErrorCode(ex), retriable(ex));
            }
        } catch (HttpException | IOException ex) {
            recordFailure(batch, safeErrorCode(ex), true);
        } catch (RetryableSourceStorageException ex) {
            recordFailure(batch, "SOURCE_STORAGE_FAILED", true);
        } catch (TransientDataAccessException ex) {
            recordFailure(batch, "TRANSIENT_DATA_ACCESS", true);
        } catch (RuntimeException ex) {
            recordFailure(batch, safeErrorCode(ex), false);
        } finally {
            if (uploaded != null && !retainUploaded) {
                storage.deleteQuietly(uploaded.location());
            }
            if (staged != null) {
                try {
                    staged.close();
                } catch (IOException ignored) {
                    // Temporary file cleanup must not change the committed reconciliation state.
                }
            }
        }
    }

    private ClaimedBatch claimNext() {
        return transactionTemplate.execute(status -> {
            LocalDateTime now = now();
            LocalDateTime staleBefore = now.minus(properties.claimTimeout());
            jdbcClient.sql("""
                            update finance_reconciliation_batch
                            set status = 'FAILED', phase = 'QUEUED', claim_token = null,
                                claimed_at = null, next_attempt_at = null,
                                last_error_code = 'ATTEMPTS_EXHAUSTED',
                                last_error_message = '微信交易账单对账重试次数已耗尽',
                                completed_at = :completedAt, version = version + 1,
                                updated_at = :updatedAt
                            where status = 'RUNNING' and claimed_at < :staleBefore
                              and attempt_count >= :maxAttempts
                            """)
                    .param("completedAt", now)
                    .param("updatedAt", now)
                    .param("staleBefore", staleBefore)
                    .param("maxAttempts", properties.maxAttempts())
                    .update();
            ClaimedBatch candidate = jdbcClient.sql("""
                            select id, mch_id, bill_date, attempt_count
                            from finance_reconciliation_batch
                            where attempt_count < :maxAttempts and ((
                                    status in ('PENDING', 'RETRY_WAIT')
                                    and (next_attempt_at is null or next_attempt_at <= :now)
                                ) or (
                                    status = 'RUNNING' and claimed_at < :staleBefore
                                ))
                            order by bill_date, id
                            limit 1
                            for update
                            """)
                    .param("now", now)
                    .param("staleBefore", staleBefore)
                    .param("maxAttempts", properties.maxAttempts())
                    .query((rs, rowNum) -> new ClaimedBatch(
                            rs.getLong("id"),
                            rs.getString("mch_id"),
                            rs.getDate("bill_date").toLocalDate(),
                            UUID.randomUUID().toString(),
                            rs.getInt("attempt_count") + 1
                    ))
                    .optional()
                    .orElse(null);
            if (candidate == null) {
                return null;
            }
            int updated = jdbcClient.sql("""
                            update finance_reconciliation_batch
                            set status = 'RUNNING', phase = 'DOWNLOAD',
                                claim_token = :claimToken, claimed_at = :claimedAt,
                                attempt_count = attempt_count + 1,
                                next_attempt_at = null, last_error_code = '', last_error_message = '',
                                started_at = coalesce(started_at, :startedAt),
                                completed_at = null, version = version + 1, updated_at = :updatedAt
                            where id = :batchId
                            """)
                    .param("claimToken", candidate.claimToken())
                    .param("claimedAt", now)
                    .param("startedAt", now)
                    .param("updatedAt", now)
                    .param("batchId", candidate.id())
                    .update();
            if (updated != 1) {
                return null;
            }
            return candidate;
        });
    }

    private void updatePhase(
            ClaimedBatch batch,
            String phase,
            ReconciliationCredential credential
    ) {
        Boolean updated = transactionTemplate.execute(status -> jdbcClient.sql("""
                        update finance_reconciliation_batch
                        set phase = :phase, credential_config_id = :configId,
                            credential_fingerprint = :fingerprint,
                            claimed_at = :claimedAt, version = version + 1, updated_at = :updatedAt
                        where id = :batchId and status = 'RUNNING' and claim_token = :claimToken
                        """)
                .param("phase", phase)
                .param("configId", credential.configId())
                .param("fingerprint", credential.fingerprint())
                .param("claimedAt", now())
                .param("updatedAt", now())
                .param("batchId", batch.id())
                .param("claimToken", batch.claimToken())
                .update() == 1);
        if (!Boolean.TRUE.equals(updated)) {
            throw new LostReconciliationClaimException();
        }
    }

    private CommitOutcome commit(
            ClaimedBatch claimed,
            ReconciliationCredential credential,
            StagedTradeBill staged,
            StoredTradeBillSource uploaded,
            ParsedTradeBill parsed,
            TradeReconciliationResult comparison
    ) {
        LockedBatch batch = lockClaimedBatch(claimed);
        if (!batch.contentSha256().isBlank()
                && !batch.contentSha256().equals(staged.contentSha256())) {
            boolean retainCandidate = recordSourceChanged(batch, staged, uploaded);
            finishBatch(claimed, "DIFFERENCES", parsed, comparison);
            return new CommitOutcome(retainCandidate, null);
        }
        boolean firstSource = batch.contentSha256().isBlank();
        if (firstSource) {
            insertEntries(batch.id(), parsed.rows());
        }
        applyDifferences(batch.id(), comparison.differences());
        long openDifferences = activeDifferenceCount(batch.id());
        String targetStatus = openDifferences > 0
                ? "DIFFERENCES" : parsed.rows().isEmpty() ? "EMPTY" : "BALANCED";
        LocalDateTime now = now();
        int updated = jdbcClient.sql("""
                        update finance_reconciliation_batch
                        set status = :status, phase = 'COMPLETE',
                            provider_hash_verified = true,
                            content_sha256 = :contentSha256,
                            storage_provider = :storageProvider,
                            storage_container = :storageContainer,
                            storage_region = :storageRegion,
                            object_key = :objectKey,
                            content_type = :contentType,
                            source_size_bytes = :sourceSizeBytes,
                            total_rows = :totalRows, payment_rows = :paymentRows,
                            refund_rows = :refundRows,
                            channel_payment_amount_cent = :channelPaymentAmountCent,
                            channel_refund_amount_cent = :channelRefundAmountCent,
                            local_payment_amount_cent = :localPaymentAmountCent,
                            local_refund_amount_cent = :localRefundAmountCent,
                            difference_count = (select count(*) from finance_reconciliation_difference d where d.batch_id = :batchId),
                            open_difference_count = :openDifferenceCount,
                            credential_config_id = :configId,
                            credential_fingerprint = :fingerprint,
                            claim_token = null, claimed_at = null, next_attempt_at = null,
                            last_error_code = '', last_error_message = '',
                            completed_at = :completedAt, version = version + 1, updated_at = :updatedAt
                        where id = :batchId and status = 'RUNNING' and claim_token = :claimToken
                        """)
                .param("status", targetStatus)
                .param("contentSha256", staged.contentSha256())
                .param("storageProvider", uploaded.location().provider().name())
                .param("storageContainer", uploaded.location().container())
                .param("storageRegion", uploaded.location().region())
                .param("objectKey", uploaded.location().objectKey())
                .param("contentType", uploaded.contentType())
                .param("sourceSizeBytes", uploaded.sizeBytes())
                .param("totalRows", parsed.rows().size())
                .param("paymentRows", parsed.paymentRows())
                .param("refundRows", parsed.refundRows())
                .param("channelPaymentAmountCent", parsed.paymentAmountCent())
                .param("channelRefundAmountCent", parsed.refundAmountCent())
                .param("localPaymentAmountCent", comparison.localPaymentAmountCent())
                .param("localRefundAmountCent", comparison.localRefundAmountCent())
                .param("openDifferenceCount", openDifferences)
                .param("configId", credential.configId())
                .param("fingerprint", credential.fingerprint())
                .param("completedAt", now)
                .param("updatedAt", now)
                .param("batchId", batch.id())
                .param("claimToken", claimed.claimToken())
                .update();
        if (updated != 1) {
            throw new LostReconciliationClaimException();
        }
        return new CommitOutcome(true, firstSource ? null : batch.sourceLocation());
    }

    private void handleNoStatement(ClaimedBatch claimed) {
        try {
            ParsedTradeBill empty = ParsedTradeBill.of(List.of());
            TradeReconciliationResult comparison = matcher.compare(
                    claimed.mchId(), claimed.billDate(), empty);
            transactionTemplate.executeWithoutResult(status -> {
                LockedBatch batch = lockClaimedBatch(claimed);
                if (!batch.contentSha256().isBlank()) {
                    recordSourceChanged(
                            batch,
                            "NO_STATEMENT_EXIST",
                            "微信本次返回无账单，但批次已存在已验证来源"
                    );
                    finishBatch(claimed, "DIFFERENCES", empty, comparison);
                    return;
                }
                applyDifferences(batch.id(), comparison.differences());
                long open = activeDifferenceCount(batch.id());
                finishBatch(claimed, open > 0 ? "DIFFERENCES" : "EMPTY", empty, comparison);
            });
        } catch (TransientDataAccessException ex) {
            recordFailure(claimed, "TRANSIENT_DATA_ACCESS", true);
        } catch (RuntimeException ex) {
            recordFailure(claimed, safeErrorCode(ex), false);
        }
    }

    private void finishBatch(
            ClaimedBatch claimed,
            String status,
            ParsedTradeBill parsed,
            TradeReconciliationResult comparison
    ) {
        long batchId = claimed.id();
        long open = activeDifferenceCount(batchId);
        LocalDateTime now = now();
        int updated = jdbcClient.sql("""
                        update finance_reconciliation_batch
                        set status = :status, phase = 'COMPLETE',
                            total_rows = case when content_sha256 = '' then :totalRows else total_rows end,
                            payment_rows = case when content_sha256 = '' then :paymentRows else payment_rows end,
                            refund_rows = case when content_sha256 = '' then :refundRows else refund_rows end,
                            channel_payment_amount_cent = case when content_sha256 = '' then :channelPaymentAmount else channel_payment_amount_cent end,
                            channel_refund_amount_cent = case when content_sha256 = '' then :channelRefundAmount else channel_refund_amount_cent end,
                            local_payment_amount_cent = :localPaymentAmount,
                            local_refund_amount_cent = :localRefundAmount,
                            difference_count = (select count(*) from finance_reconciliation_difference d where d.batch_id = :batchId),
                            open_difference_count = :openDifferenceCount,
                            claim_token = null, claimed_at = null, next_attempt_at = null,
                            last_error_code = '', last_error_message = '', completed_at = :completedAt,
                            version = version + 1, updated_at = :updatedAt
                        where id = :batchId and status = 'RUNNING' and claim_token = :claimToken
                        """)
                .param("status", status)
                .param("totalRows", parsed.rows().size())
                .param("paymentRows", parsed.paymentRows())
                .param("refundRows", parsed.refundRows())
                .param("channelPaymentAmount", parsed.paymentAmountCent())
                .param("channelRefundAmount", parsed.refundAmountCent())
                .param("localPaymentAmount", comparison.localPaymentAmountCent())
                .param("localRefundAmount", comparison.localRefundAmountCent())
                .param("openDifferenceCount", open)
                .param("completedAt", now)
                .param("updatedAt", now)
                .param("batchId", batchId)
                .param("claimToken", claimed.claimToken())
                .update();
        if (updated != 1) {
            throw new LostReconciliationClaimException();
        }
    }

    private void insertEntries(long batchId, List<TradeBillRow> rows) {
        String sql = """
                insert into wechat_trade_bill_entry
                    (batch_id, row_no, entry_type, transaction_id, out_trade_no,
                     refund_id, out_refund_no, occurred_at, amount_cent, currency,
                     channel_status, row_digest, created_at)
                values
                    (:batchId, :rowNo, :entryType, :transactionId, :outTradeNo,
                     :refundId, :outRefundNo, :occurredAt, :amountCent, :currency,
                     :channelStatus, :rowDigest, :createdAt)
                """;
        for (int start = 0; start < rows.size(); start += ENTRY_INSERT_CHUNK) {
            int end = Math.min(start + ENTRY_INSERT_CHUNK, rows.size());
            MapSqlParameterSource[] parameters = new MapSqlParameterSource[end - start];
            for (int index = start; index < end; index++) {
                TradeBillRow row = rows.get(index);
                parameters[index - start] = new MapSqlParameterSource()
                        .addValue("batchId", batchId)
                        .addValue("rowNo", row.rowNo())
                        .addValue("entryType", row.entryType().name())
                        .addValue("transactionId", row.transactionId())
                        .addValue("outTradeNo", row.outTradeNo())
                        .addValue("refundId", row.refundId())
                        .addValue("outRefundNo", row.outRefundNo())
                        .addValue("occurredAt", row.occurredAt())
                        .addValue("amountCent", row.amountCent())
                        .addValue("currency", row.currency())
                        .addValue("channelStatus", row.channelStatus())
                        .addValue("rowDigest", row.rowDigest())
                        .addValue("createdAt", now());
            }
            namedJdbc.batchUpdate(sql, parameters);
        }
    }

    void applyDifferences(long batchId, List<DifferenceDraft> drafts) {
        reconcileDifferences(batchId, drafts, true);
    }

    void reconcileDifferences(
            long batchId,
            List<DifferenceDraft> drafts,
            boolean autoClearMissing
    ) {
        Map<String, ExistingDifference> existing = new HashMap<>();
        for (ExistingDifference difference : jdbcClient.sql("""
                        select id, diff_key, difference_type, status, version,
                               external_refund_applied
                        from finance_reconciliation_difference
                        where batch_id = :batchId
                        order by id
                        for update
                        """)
                .param("batchId", batchId)
                .query((rs, rowNum) -> new ExistingDifference(
                        rs.getLong("id"),
                        rs.getString("diff_key"),
                        rs.getString("difference_type"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getBoolean("external_refund_applied")
                ))
                .list()) {
            existing.put(difference.diffKey(), difference);
        }
        Set<String> currentKeys = new HashSet<>();
        List<DifferenceDraft> newDifferences = new ArrayList<>();
        for (DifferenceDraft draft : drafts) {
            currentKeys.add(draft.diffKey());
            ExistingDifference prior = existing.get(draft.diffKey());
            if (prior == null) {
                newDifferences.add(draft);
            } else {
                refreshDifference(batchId, prior, draft);
            }
        }
        insertDifferences(batchId, newDifferences);
        if (!autoClearMissing) {
            return;
        }
        for (ExistingDifference prior : existing.values()) {
            if (currentKeys.contains(prior.diffKey())
                    || "SOURCE_CHANGED".equals(prior.differenceType())
                    || !("OPEN".equals(prior.status()) || "INVESTIGATING".equals(prior.status()))) {
                continue;
            }
            LocalDateTime now = now();
            int updated = jdbcClient.sql("""
                            update finance_reconciliation_difference
                            set status = 'AUTO_CLEARED', resolution_code = 'EVIDENCE_MATCHED',
                                resolution_reason = '重新比对后差异已消失', resolved_by = null,
                                resolved_at = :resolvedAt, version = version + 1, updated_at = :updatedAt
                            where id = :differenceId and version = :version
                            """)
                    .param("resolvedAt", now)
                    .param("updatedAt", now)
                    .param("differenceId", prior.id())
                    .param("version", prior.version())
                    .update();
            if (updated == 1) {
                auditDifference(
                        batchId, prior.id(), prior.status(), "AUTO_CLEARED", "AUTO_CLEAR",
                        "EVIDENCE_MATCHED", "重新比对后差异已消失");
            }
        }
    }

    private void refreshDifference(
            long batchId,
            ExistingDifference prior,
            DifferenceDraft draft
    ) {
        boolean reopen = !prior.externalRefundApplied() && ("RESOLVED".equals(prior.status())
                || "AUTO_CLEARED".equals(prior.status()));
        String targetStatus = reopen ? "OPEN" : prior.status();
        LocalDateTime now = now();
        int updated = jdbcClient.sql("""
                        update finance_reconciliation_difference
                        set severity = :severity, status = :targetStatus,
                            transaction_id = :transactionId, out_trade_no = :outTradeNo,
                            refund_id = :refundId, out_refund_no = :outRefundNo,
                            order_id = :orderId, payment_order_id = :paymentOrderId,
                            refund_order_id = :refundOrderId,
                            provider_amount_cent = :providerAmountCent,
                            local_amount_cent = case when :externalRefundApplied
                                then local_amount_cent else :localAmountCent end,
                            provider_status = :providerStatus,
                            provider_evidence = :providerEvidence,
                            local_status = case when :externalRefundApplied
                                then local_status else :localStatus end,
                            local_evidence = case when :externalRefundApplied
                                then local_evidence else :localEvidence end,
                            candidate_content_sha256 = :candidateContentSha256,
                            candidate_storage_provider = :candidateStorageProvider,
                            candidate_storage_container = :candidateStorageContainer,
                            candidate_storage_region = :candidateStorageRegion,
                            candidate_object_key = :candidateObjectKey,
                            candidate_size_bytes = :candidateSizeBytes,
                            resolution_code = case when :reopen then '' else resolution_code end,
                            resolution_reason = case when :reopen then '' else resolution_reason end,
                            resolved_by = case when :reopen then null else resolved_by end,
                            resolved_at = case when :reopen then null else resolved_at end,
                            version = version + 1, updated_at = :updatedAt
                        where id = :differenceId and version = :version
                        """)
                .param("severity", draft.severity().name())
                .param("targetStatus", targetStatus)
                .param("transactionId", draft.transactionId())
                .param("outTradeNo", draft.outTradeNo())
                .param("refundId", draft.refundId())
                .param("outRefundNo", draft.outRefundNo())
                .param("orderId", draft.orderId())
                .param("paymentOrderId", draft.paymentOrderId())
                .param("refundOrderId", draft.refundOrderId())
                .param("providerAmountCent", draft.providerAmountCent())
                .param("localAmountCent", draft.localAmountCent())
                .param("providerStatus", draft.providerStatus())
                .param("localStatus", draft.localStatus())
                .param("providerEvidence", draft.providerEvidence())
                .param("localEvidence", draft.localEvidence())
                .param("externalRefundApplied", prior.externalRefundApplied())
                .param("candidateContentSha256", draft.candidateContentSha256())
                .param("candidateStorageProvider", draft.candidateStorageProvider())
                .param("candidateStorageContainer", draft.candidateStorageContainer())
                .param("candidateStorageRegion", draft.candidateStorageRegion())
                .param("candidateObjectKey", draft.candidateObjectKey())
                .param("candidateSizeBytes", draft.candidateSizeBytes())
                .param("reopen", reopen)
                .param("updatedAt", now)
                .param("differenceId", prior.id())
                .param("version", prior.version())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Finance reconciliation difference update conflicted");
        }
        if (reopen) {
            auditDifference(
                    batchId, prior.id(), prior.status(), "OPEN", "REOPEN", "", "差异再次出现");
        }
    }

    private void insertDifferences(long batchId, List<DifferenceDraft> drafts) {
        if (drafts.isEmpty()) {
            return;
        }
        LocalDateTime now = now();
        String sql = """
                        insert into finance_reconciliation_difference
                            (batch_id, diff_key, difference_type, severity, status,
                             transaction_id, out_trade_no, refund_id, out_refund_no,
                             order_id, payment_order_id, refund_order_id,
                             provider_amount_cent, local_amount_cent, provider_status, local_status,
                             provider_evidence, local_evidence,
                             candidate_content_sha256, candidate_storage_provider,
                             candidate_storage_container, candidate_storage_region,
                             candidate_object_key, candidate_size_bytes,
                             created_at, updated_at)
                        values
                            (:batchId, :diffKey, :differenceType, :severity, 'OPEN',
                             :transactionId, :outTradeNo, :refundId, :outRefundNo,
                             :orderId, :paymentOrderId, :refundOrderId,
                             :providerAmountCent, :localAmountCent, :providerStatus, :localStatus,
                             :providerEvidence, :localEvidence,
                             :candidateContentSha256, :candidateStorageProvider,
                             :candidateStorageContainer, :candidateStorageRegion,
                             :candidateObjectKey, :candidateSizeBytes,
                             :createdAt, :updatedAt)
                        """;
        for (int start = 0; start < drafts.size(); start += DIFFERENCE_INSERT_CHUNK) {
            int end = Math.min(start + DIFFERENCE_INSERT_CHUNK, drafts.size());
            MapSqlParameterSource[] parameters = drafts.subList(start, end).stream()
                    .map(draft -> differenceParameters(batchId, draft, now))
                    .toArray(MapSqlParameterSource[]::new);
            int[] inserted = namedJdbc.batchUpdate(sql, parameters);
            for (int count : inserted) {
                if (count != 1 && count != java.sql.Statement.SUCCESS_NO_INFO) {
                    throw new IllegalStateException(
                            "Finance reconciliation difference batch insert failed");
                }
            }
        }
    }

    private MapSqlParameterSource differenceParameters(
            long batchId,
            DifferenceDraft draft,
            LocalDateTime now
    ) {
        return new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("diffKey", draft.diffKey())
                .addValue("differenceType", draft.type().name())
                .addValue("severity", draft.severity().name())
                .addValue("transactionId", draft.transactionId())
                .addValue("outTradeNo", draft.outTradeNo())
                .addValue("refundId", draft.refundId())
                .addValue("outRefundNo", draft.outRefundNo())
                .addValue("orderId", draft.orderId())
                .addValue("paymentOrderId", draft.paymentOrderId())
                .addValue("refundOrderId", draft.refundOrderId())
                .addValue("providerAmountCent", draft.providerAmountCent())
                .addValue("localAmountCent", draft.localAmountCent())
                .addValue("providerStatus", draft.providerStatus())
                .addValue("localStatus", draft.localStatus())
                .addValue("providerEvidence", draft.providerEvidence())
                .addValue("localEvidence", draft.localEvidence())
                .addValue("candidateContentSha256", draft.candidateContentSha256())
                .addValue("candidateStorageProvider", draft.candidateStorageProvider())
                .addValue("candidateStorageContainer", draft.candidateStorageContainer())
                .addValue("candidateStorageRegion", draft.candidateStorageRegion())
                .addValue("candidateObjectKey", draft.candidateObjectKey())
                .addValue("candidateSizeBytes", draft.candidateSizeBytes())
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private boolean recordSourceChanged(
            LockedBatch batch,
            StagedTradeBill staged,
            StoredTradeBillSource uploaded
    ) {
        String diffKey = sourceChangedKey(batch, staged.contentSha256());
        CandidateSourceEvidence retained = jdbcClient.sql("""
                        select provider_evidence, candidate_content_sha256,
                               candidate_storage_provider, candidate_storage_container,
                               candidate_storage_region, candidate_object_key,
                               candidate_size_bytes
                        from finance_reconciliation_difference
                        where batch_id = :batchId and diff_key = :diffKey
                        """)
                .param("batchId", batch.id())
                .param("diffKey", diffKey)
                .query((rs, rowNum) -> new CandidateSourceEvidence(
                        rs.getString("provider_evidence"),
                        rs.getString("candidate_content_sha256"),
                        rs.getString("candidate_storage_provider"),
                        rs.getString("candidate_storage_container"),
                        rs.getString("candidate_storage_region"),
                        rs.getString("candidate_object_key"),
                        rs.getObject("candidate_size_bytes", Long.class)
                ))
                .optional()
                .orElse(null);
        boolean firstCandidate = retained == null;
        if (firstCandidate) {
            retained = new CandidateSourceEvidence(
                    json(Map.of(
                            "oldContentSha256", batch.contentSha256(),
                            "newContentSha256", staged.contentSha256(),
                            "candidateSizeBytes", uploaded.sizeBytes()
                    )),
                    staged.contentSha256(),
                    uploaded.location().provider().name(),
                    uploaded.location().container(),
                    uploaded.location().region(),
                    uploaded.location().objectKey(),
                    uploaded.sizeBytes()
            );
        }
        DifferenceDraft draft = new DifferenceDraft(
                diffKey,
                ReconciliationDifferenceType.SOURCE_CHANGED,
                ReconciliationDifferenceSeverity.CRITICAL,
                "", "", "", "", null, null, null,
                null, null, "SOURCE_CHANGED", "VERIFIED_SOURCE",
                retained.providerEvidence(), "{}",
                retained.contentSha256(), retained.storageProvider(),
                retained.storageContainer(), retained.storageRegion(),
                retained.objectKey(), retained.sizeBytes()
        );
        reconcileDifferences(batch.id(), List.of(draft), false);
        return firstCandidate;
    }

    private void recordSourceChanged(LockedBatch batch, String candidateState, String description) {
        sourceChangedDifference(batch, candidateState, json(Map.of(
                "oldContentSha256", batch.contentSha256(),
                "candidateState", candidateState,
                "description", description
        )));
    }

    private void sourceChangedDifference(
            LockedBatch batch,
            String candidateIdentity,
            String providerEvidence
    ) {
        String diffKey = sourceChangedKey(batch, candidateIdentity);
        DifferenceDraft draft = new DifferenceDraft(
                diffKey,
                ReconciliationDifferenceType.SOURCE_CHANGED,
                ReconciliationDifferenceSeverity.CRITICAL,
                "", "", "", "", null, null, null,
                null, null, "SOURCE_CHANGED", "VERIFIED_SOURCE", providerEvidence, "{}"
        );
        reconcileDifferences(batch.id(), List.of(draft), false);
    }

    private String sourceChangedKey(LockedBatch batch, String candidateIdentity) {
        return sha256("SOURCE_CHANGED|" + batch.contentSha256() + "|" + candidateIdentity);
    }

    private void auditDifference(
            long batchId,
            long differenceId,
            String fromStatus,
            String toStatus,
            String action,
            String resolutionCode,
            String reason
    ) {
        jdbcClient.sql("""
                        insert into finance_reconciliation_resolution_audit
                            (batch_id, difference_id, from_status, to_status, action,
                             resolution_code, reason, metadata, operator_id, created_at)
                        values
                            (:batchId, :differenceId, :fromStatus, :toStatus, :action,
                             :resolutionCode, :reason, '', null, :createdAt)
                        """)
                .param("batchId", batchId)
                .param("differenceId", differenceId)
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("action", action)
                .param("resolutionCode", resolutionCode)
                .param("reason", reason)
                .param("createdAt", now())
                .update();
    }

    private LockedBatch lockClaimedBatch(ClaimedBatch claimed) {
        return jdbcClient.sql("""
                        select id, content_sha256, storage_provider, storage_container,
                               storage_region, object_key
                        from finance_reconciliation_batch
                        where id = :batchId and status = 'RUNNING' and claim_token = :claimToken
                        for update
                        """)
                .param("batchId", claimed.id())
                .param("claimToken", claimed.claimToken())
                .query((rs, rowNum) -> new LockedBatch(
                        rs.getLong("id"),
                        rs.getString("content_sha256"),
                        sourceLocation(
                                rs.getString("storage_provider"),
                                rs.getString("storage_container"),
                                rs.getString("storage_region"),
                                rs.getString("object_key")
                        )))
                .optional()
                .orElseThrow(LostReconciliationClaimException::new);
    }

    private long activeDifferenceCount(long batchId) {
        return jdbcClient.sql("""
                        select count(*) from finance_reconciliation_difference
                        where batch_id = :batchId and status in ('OPEN', 'INVESTIGATING')
                        """)
                .param("batchId", batchId)
                .query(Long.class)
                .single();
    }

    private void recordFailure(ClaimedBatch batch, String errorCode, boolean retryable) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                LocalDateTime now = now();
                boolean exhausted = batch.attemptCount() >= properties.maxAttempts();
                String target = retryable && !exhausted ? "RETRY_WAIT" : "FAILED";
                LocalDateTime nextAttemptAt = "RETRY_WAIT".equals(target)
                        ? now.plus(retryDelay(batch.attemptCount())) : null;
                jdbcClient.sql("""
                                update finance_reconciliation_batch
                                set status = :status, phase = 'QUEUED',
                                    claim_token = null, claimed_at = null,
                                    next_attempt_at = :nextAttemptAt,
                                    last_error_code = :errorCode,
                                    last_error_message = :errorMessage,
                                    completed_at = case when :failed then :completedAt else null end,
                                    version = version + 1, updated_at = :updatedAt
                                where id = :batchId and status = 'RUNNING' and claim_token = :claimToken
                                """)
                        .param("status", target)
                        .param("nextAttemptAt", nextAttemptAt)
                        .param("errorCode", errorCode)
                        .param("errorMessage", safeErrorMessage(errorCode))
                        .param("failed", "FAILED".equals(target))
                        .param("completedAt", now)
                        .param("updatedAt", now)
                        .param("batchId", batch.id())
                        .param("claimToken", batch.claimToken())
                        .update();
            });
            log.warn(
                    "WeChat trade reconciliation attempt ended safely: batchId={}, code={}, attempt={}",
                    batch.id(), errorCode, batch.attemptCount());
        } catch (RuntimeException ignored) {
            log.warn(
                    "WeChat trade reconciliation failure could not be recorded: batchId={}, code={}",
                    batch.id(), errorCode);
        }
    }

    private boolean retriable(ServiceException exception) {
        String code = exception.getErrorCode();
        return exception.getHttpStatusCode() == 429
                || exception.getHttpStatusCode() >= 500
                || "STATEMENT_CREATING".equals(code)
                || "FREQUENCY_LIMITED".equals(code)
                || "SYSTEM_ERROR".equals(code);
    }

    private String providerErrorCode(ServiceException exception) {
        String code = exception.getErrorCode();
        if (code == null || !code.matches("[A-Z0-9_]{1,64}")) {
            return "WECHAT_SERVICE_ERROR";
        }
        return code;
    }

    private String safeErrorCode(Throwable throwable) {
        if (throwable instanceof LostReconciliationClaimException) {
            return "CLAIM_LOST";
        }
        if (throwable instanceof IOException) {
            return "BILL_IO_OR_INTEGRITY_FAILED";
        }
        return "RECONCILIATION_FAILED";
    }

    private String safeErrorMessage(String code) {
        return switch (code) {
            case "STATEMENT_CREATING" -> "微信交易账单仍在生成，系统将稍后重试";
            case "FREQUENCY_LIMITED" -> "微信交易账单接口限流，系统将稍后重试";
            case "BILL_IO_OR_INTEGRITY_FAILED" -> "交易账单下载、完整性校验或解析失败";
            case "SOURCE_STORAGE_FAILED" -> "交易账单私有存储暂时不可用，系统将稍后重试";
            case "TRANSIENT_DATA_ACCESS" -> "数据库暂时不可用，系统将稍后重试";
            case "CLAIM_LOST" -> "批次处理租约已被其他实例接管";
            default -> "微信交易账单对账处理失败";
        };
    }

    private Duration retryDelay(int attemptCount) {
        long factor = 1L << Math.min(Math.max(attemptCount - 1, 0), 20);
        Duration calculated;
        try {
            calculated = properties.retryBase().multipliedBy(factor);
        } catch (ArithmeticException ex) {
            calculated = properties.retryMax();
        }
        return calculated.compareTo(properties.retryMax()) > 0
                ? properties.retryMax() : calculated;
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize reconciliation evidence", ex);
        }
    }

    private String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(TimePolicy.UTC));
    }

    private record ClaimedBatch(
            long id,
            String mchId,
            LocalDate billDate,
            String claimToken,
            int attemptCount
    ) {
    }

    private StorageObjectLocation sourceLocation(
            String provider,
            String container,
            String region,
            String objectKey
    ) {
        if (provider == null || provider.isBlank() || objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return new StorageObjectLocation(
                org.muybaby.shopserver.storage.StorageProviderKind.valueOf(provider),
                container,
                region,
                objectKey
        );
    }

    private record LockedBatch(
            long id,
            String contentSha256,
            StorageObjectLocation sourceLocation
    ) {
    }

    private record ExistingDifference(
            long id,
            String diffKey,
            String differenceType,
            String status,
            long version,
            boolean externalRefundApplied
    ) {
    }

    private record CommitOutcome(
            boolean retainUploaded,
            StorageObjectLocation deleteAfterCommit
    ) {
    }

    private record CandidateSourceEvidence(
            String providerEvidence,
            String contentSha256,
            String storageProvider,
            String storageContainer,
            String storageRegion,
            String objectKey,
            Long sizeBytes
    ) {
    }

    private static final class LostReconciliationClaimException extends RuntimeException {
        private LostReconciliationClaimException() {
            super("Finance reconciliation claim was lost");
        }
    }

    private static final class RetryableSourceStorageException extends RuntimeException {
        private RetryableSourceStorageException(RuntimeException cause) {
            super("Finance reconciliation source storage failed", cause);
        }
    }
}
