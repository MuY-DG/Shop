package org.muybaby.shopserver.finance.reconciliation.service;

import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpRequest;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.finance.reconciliation.download.StagedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.download.TradeBillDownloadService;
import org.muybaby.shopserver.finance.reconciliation.parser.ParsedTradeBill;
import org.muybaby.shopserver.finance.reconciliation.parser.WechatTradeBillParser;
import org.muybaby.shopserver.finance.reconciliation.storage.FinanceTradeBillStorage;
import org.muybaby.shopserver.finance.reconciliation.storage.StoredTradeBillSource;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class TradeReconciliationProcessorClaimTest {

    @Autowired
    private TradeReconciliationProcessor processor;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private ReconciliationCredentialCatalog credentialCatalog;

    @MockitoBean
    private TradeBillDownloadService downloadService;

    @MockitoBean
    private WechatTradeBillParser parser;

    @MockitoBean
    private FinanceTradeBillStorage storage;

    @MockitoBean
    private TradeReconciliationMatcher matcher;

    @Test
    void pendingAndStaleClaimsAreAcquiredWithTokenThenCompletedAndReleased() throws Exception {
        long pendingId = 9_361_001L;
        long staleId = 9_361_002L;
        deleteBatch(pendingId);
        deleteBatch(staleId);
        insertBatch(pendingId, "PENDING", null, null, 0);
        insertBatch(
                staleId,
                "RUNNING",
                "stale-token",
                LocalDateTime.of(2000, 1, 1, 0, 0),
                1);
        try {
            stubSuccessfulProcessing("mch-claim-" + pendingId);
            assertThat(processor.processNext()).isTrue();
            assertCompleted(pendingId, 1);

            stubSuccessfulProcessing("mch-claim-" + staleId);
            assertThat(processor.processNext()).isTrue();
            assertCompleted(staleId, 2);
        } finally {
            deleteBatch(pendingId);
            deleteBatch(staleId);
        }
    }

    @Test
    void transientDatabaseFailureMovesClaimToRetryWaitWithBackoff() throws Exception {
        long batchId = 9_361_003L;
        deleteBatch(batchId);
        insertBatch(batchId, "PENDING", null, null, 0);
        String mchId = "mch-claim-" + batchId;
        ResolvedPaymentConfig config = mock(ResolvedPaymentConfig.class);
        when(credentialCatalog.require(eq(mchId), eq(LocalDate.of(2026, 8, 8))))
                .thenReturn(new ReconciliationCredential(mchId, 7L, "f".repeat(64), config));
        when(downloadService.download(eq(config), eq(LocalDate.of(2026, 8, 8))))
                .thenThrow(new CannotAcquireLockException("simulated transient lock failure"));
        try {
            assertThat(processor.processNext()).isTrue();
            RetryState state = jdbcClient.sql("""
                            select status, attempt_count, claim_token, claimed_at, next_attempt_at,
                                   last_error_code
                            from finance_reconciliation_batch where id = :id
                            """)
                    .param("id", batchId)
                    .query((rs, rowNum) -> new RetryState(
                            rs.getString("status"),
                            rs.getInt("attempt_count"),
                            rs.getString("claim_token"),
                            rs.getObject("claimed_at", LocalDateTime.class),
                            rs.getObject("next_attempt_at", LocalDateTime.class),
                            rs.getString("last_error_code")
                    ))
                    .single();
            assertThat(state.status()).isEqualTo("RETRY_WAIT");
            assertThat(state.attemptCount()).isOne();
            assertThat(state.claimToken()).isNull();
            assertThat(state.claimedAt()).isNull();
            assertThat(state.nextAttemptAt()).isNotNull();
            assertThat(state.errorCode()).isEqualTo("TRANSIENT_DATA_ACCESS");
        } finally {
            deleteBatch(batchId);
        }
    }

    @Test
    void sameDigestRetryAtomicallyRepairsStoredSourceAndDeletesOldObject() throws Exception {
        long batchId = 9_361_004L;
        deleteBatch(batchId);
        insertBatch(batchId, "PENDING", null, null, 0);
        StorageObjectLocation oldLocation = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test",
                "ap-test",
                "private/finance/old-source.csv");
        setExistingSource(batchId, oldLocation, "a".repeat(64), 3L);
        try {
            stubSuccessfulProcessing("mch-claim-" + batchId);

            assertThat(processor.processNext()).isTrue();

            assertThat(jdbcClient.sql("""
                            select object_key from finance_reconciliation_batch where id = :id
                            """)
                    .param("id", batchId)
                    .query(String.class)
                    .single()).isEqualTo("private/finance/mch-claim-" + batchId + ".csv");
            verify(storage).deleteQuietly(oldLocation);
        } finally {
            deleteBatch(batchId);
        }
    }

    @Test
    void noStatementComparisonTransientFailureAlsoUsesRetryBackoff() throws Exception {
        long batchId = 9_361_006L;
        deleteBatch(batchId);
        insertBatch(batchId, "PENDING", null, null, 0);
        String mchId = "mch-claim-" + batchId;
        LocalDate billDate = LocalDate.of(2026, 8, 8);
        ResolvedPaymentConfig config = mock(ResolvedPaymentConfig.class);
        when(credentialCatalog.require(eq(mchId), eq(billDate)))
                .thenReturn(new ReconciliationCredential(mchId, 7L, "f".repeat(64), config));
        ServiceException noStatement = new ServiceException(
                mock(HttpRequest.class),
                404,
                "{\"code\":\"NO_STATEMENT_EXIST\",\"message\":\"not ready\"}"
        );
        when(downloadService.download(eq(config), eq(billDate))).thenThrow(noStatement);
        when(matcher.compare(eq(mchId), eq(billDate), any(ParsedTradeBill.class)))
                .thenThrow(new CannotAcquireLockException("transient matcher query"));
        try {
            assertThat(processor.processNext()).isTrue();
            assertThat(jdbcClient.sql("""
                            select status from finance_reconciliation_batch where id = :id
                            """)
                    .param("id", batchId)
                    .query(String.class)
                    .single()).isEqualTo("RETRY_WAIT");
            assertThat(jdbcClient.sql("""
                            select last_error_code from finance_reconciliation_batch where id = :id
                            """)
                    .param("id", batchId)
                    .query(String.class)
                    .single()).isEqualTo("TRANSIENT_DATA_ACCESS");
        } finally {
            deleteBatch(batchId);
        }
    }

    @Test
    void repeatedSourceChangedCandidateKeepsFirstEvidenceAndDeletesDuplicateUpload()
            throws Exception {
        long batchId = 9_361_005L;
        deleteBatch(batchId);
        insertBatch(batchId, "PENDING", null, null, 0);
        StorageObjectLocation original = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test",
                "ap-test",
                "private/finance/original-source.csv");
        setExistingSource(batchId, original, "0".repeat(64), 3L);
        String mchId = "mch-claim-" + batchId;
        LocalDate billDate = LocalDate.of(2026, 8, 8);
        ResolvedPaymentConfig config = mock(ResolvedPaymentConfig.class);
        when(credentialCatalog.require(eq(mchId), eq(billDate)))
                .thenReturn(new ReconciliationCredential(mchId, 7L, "f".repeat(64), config));
        StagedTradeBill first = staged("candidate-one", "a".repeat(64));
        StagedTradeBill duplicate = staged("candidate-two", "a".repeat(64));
        when(downloadService.download(eq(config), eq(billDate))).thenReturn(first, duplicate);
        when(parser.parse(any(Path.class))).thenReturn(ParsedTradeBill.of(List.of()));
        StorageObjectLocation firstLocation = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test",
                "ap-test",
                "private/finance/candidate-first.csv");
        StorageObjectLocation duplicateLocation = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test",
                "ap-test",
                "private/finance/candidate-duplicate.csv");
        when(storage.store(eq(mchId), eq(billDate), any(StagedTradeBill.class)))
                .thenReturn(
                        new StoredTradeBillSource(
                                firstLocation,
                                FinanceTradeBillStorage.CONTENT_TYPE,
                                first.sizeBytes(),
                                first.contentSha256()),
                        new StoredTradeBillSource(
                                duplicateLocation,
                                FinanceTradeBillStorage.CONTENT_TYPE,
                                duplicate.sizeBytes(),
                                duplicate.contentSha256())
                );
        when(matcher.compare(eq(mchId), eq(billDate), any(ParsedTradeBill.class)))
                .thenReturn(new TradeReconciliationResult(List.of(), 0L, 0L));
        try {
            assertThat(processor.processNext()).isTrue();
            jdbcClient.sql("""
                            update finance_reconciliation_batch
                            set status = 'PENDING', phase = 'QUEUED', attempt_count = 0,
                                completed_at = null, version = version + 1
                            where id = :id
                            """)
                    .param("id", batchId)
                    .update();
            assertThat(processor.processNext()).isTrue();

            CandidateState candidate = jdbcClient.sql("""
                            select candidate_content_sha256, candidate_object_key,
                                   candidate_size_bytes
                            from finance_reconciliation_difference
                            where batch_id = :id and difference_type = 'SOURCE_CHANGED'
                            """)
                    .param("id", batchId)
                    .query((rs, rowNum) -> new CandidateState(
                            rs.getString("candidate_content_sha256"),
                            rs.getString("candidate_object_key"),
                            rs.getLong("candidate_size_bytes")
                    ))
                    .single();
            assertThat(candidate.contentSha256()).isEqualTo("a".repeat(64));
            assertThat(candidate.objectKey()).isEqualTo(firstLocation.objectKey());
            assertThat(candidate.sizeBytes()).isEqualTo(first.sizeBytes());
            verify(storage).deleteQuietly(duplicateLocation);
        } finally {
            deleteBatch(batchId);
        }
    }

    private void stubSuccessfulProcessing(String mchId) throws IOException {
        LocalDate billDate = LocalDate.of(2026, 8, 8);
        ResolvedPaymentConfig config = mock(ResolvedPaymentConfig.class);
        when(credentialCatalog.require(eq(mchId), eq(billDate)))
                .thenReturn(new ReconciliationCredential(mchId, 7L, "f".repeat(64), config));
        Path path = Files.createTempFile("finance-claim-test-", ".csv");
        Files.writeString(path, "csv");
        StagedTradeBill staged = new StagedTradeBill(path, 3L, "a".repeat(64), true);
        when(downloadService.download(eq(config), eq(billDate))).thenReturn(staged);
        when(parser.parse(eq(path))).thenReturn(ParsedTradeBill.of(List.of()));
        StorageObjectLocation location = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test",
                "ap-test",
                "private/finance/" + mchId + ".csv");
        when(storage.store(eq(mchId), eq(billDate), any(StagedTradeBill.class)))
                .thenReturn(new StoredTradeBillSource(
                        location, FinanceTradeBillStorage.CONTENT_TYPE, 3L, "a".repeat(64)));
        when(matcher.compare(eq(mchId), eq(billDate), any(ParsedTradeBill.class)))
                .thenReturn(new TradeReconciliationResult(List.of(), 0L, 0L));
    }

    private StagedTradeBill staged(String prefix, String contentSha256) throws IOException {
        Path path = Files.createTempFile(prefix, ".csv");
        Files.writeString(path, "csv");
        return new StagedTradeBill(path, 3L, contentSha256, true);
    }

    private void assertCompleted(long batchId, int attempts) {
        CompletedState state = jdbcClient.sql("""
                        select status, phase, attempt_count, claim_token, claimed_at,
                               provider_hash_verified, content_sha256, object_key
                        from finance_reconciliation_batch where id = :id
                        """)
                .param("id", batchId)
                .query((rs, rowNum) -> new CompletedState(
                        rs.getString("status"),
                        rs.getString("phase"),
                        rs.getInt("attempt_count"),
                        rs.getString("claim_token"),
                        rs.getObject("claimed_at", LocalDateTime.class),
                        rs.getBoolean("provider_hash_verified"),
                        rs.getString("content_sha256"),
                        rs.getString("object_key")
                ))
                .single();
        assertThat(state.status()).isEqualTo("EMPTY");
        assertThat(state.phase()).isEqualTo("COMPLETE");
        assertThat(state.attemptCount()).isEqualTo(attempts);
        assertThat(state.claimToken()).isNull();
        assertThat(state.claimedAt()).isNull();
        assertThat(state.providerHashVerified()).isTrue();
        assertThat(state.contentSha256()).isEqualTo("a".repeat(64));
        assertThat(state.objectKey()).isNotBlank();
    }

    private void insertBatch(
            long id,
            String status,
            String claimToken,
            LocalDateTime claimedAt,
            int attempts
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (id, mch_id, bill_date, bill_type, status, phase,
                             claim_token, claimed_at, attempt_count,
                             requested_at, created_at, updated_at)
                        values
                            (:id, :mchId, date '2026-08-08', 'TRADE_ALL', :status, 'QUEUED',
                             :claimToken, :claimedAt, :attempts, :now, :now, :now)
                        """)
                .param("id", id)
                .param("mchId", "mch-claim-" + id)
                .param("status", status)
                .param("claimToken", claimToken)
                .param("claimedAt", claimedAt)
                .param("attempts", attempts)
                .param("now", now)
                .update();
    }

    private void setExistingSource(
            long batchId,
            StorageObjectLocation location,
            String contentSha256,
            long sizeBytes
    ) {
        jdbcClient.sql("""
                        update finance_reconciliation_batch
                        set provider_hash_verified = true, content_sha256 = :contentSha256,
                            storage_provider = :provider, storage_container = :container,
                            storage_region = :region, object_key = :objectKey,
                            content_type = :contentType, source_size_bytes = :sizeBytes
                        where id = :id
                        """)
                .param("contentSha256", contentSha256)
                .param("provider", location.provider().name())
                .param("container", location.container())
                .param("region", location.region())
                .param("objectKey", location.objectKey())
                .param("contentType", FinanceTradeBillStorage.CONTENT_TYPE)
                .param("sizeBytes", sizeBytes)
                .param("id", batchId)
                .update();
    }

    private void deleteBatch(long batchId) {
        jdbcClient.sql("delete from finance_reconciliation_resolution_audit where batch_id = :id")
                .param("id", batchId)
                .update();
        jdbcClient.sql("delete from finance_reconciliation_difference where batch_id = :id")
                .param("id", batchId)
                .update();
        jdbcClient.sql("delete from wechat_trade_bill_entry where batch_id = :id")
                .param("id", batchId)
                .update();
        jdbcClient.sql("delete from finance_reconciliation_batch where id = :id")
                .param("id", batchId)
                .update();
    }

    private record CompletedState(
            String status,
            String phase,
            int attemptCount,
            String claimToken,
            LocalDateTime claimedAt,
            boolean providerHashVerified,
            String contentSha256,
            String objectKey
    ) {
    }

    private record RetryState(
            String status,
            int attemptCount,
            String claimToken,
            LocalDateTime claimedAt,
            LocalDateTime nextAttemptAt,
            String errorCode
    ) {
    }

    private record CandidateState(
            String contentSha256,
            String objectKey,
            long sizeBytes
    ) {
    }
}
