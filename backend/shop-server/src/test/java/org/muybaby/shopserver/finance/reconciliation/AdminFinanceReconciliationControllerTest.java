package org.muybaby.shopserver.finance.reconciliation;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminFinanceReconciliationControllerTest {

    @Autowired
    private AdminFinanceReconciliationController controller;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void readPermissionReturnsBigintIdsAsJsonStringsButCannotDownloadSource() throws Exception {
        long batchId = insertBatchWithoutSource();
        String token = issueAdminToken(
                jdbcClient, opaqueTokenService, List.of("finance:reconciliation:read"));

        mockMvc.perform(get("/admin/finance/reconciliation/batches/{id}", batchId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(batchId)))
                .andExpect(jsonPath("$.data.version").isNumber());

        mockMvc.perform(get("/admin/finance/reconciliation/batches/{id}/source", batchId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void sourceAccessIsAuditedBeforePartialReadAndUsesPrivateNoStoreHeaders() throws Exception {
        byte[] content = "private-finance-source".getBytes(StandardCharsets.UTF_8);
        StorageObjectLocation location = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test-bucket",
                "ap-test",
                "private/finance/source-partial.csv");
        storageProvider.put(
                location,
                "text/csv; charset=UTF-8",
                new ByteArrayInputStream(content),
                content.length);
        long batchId = insertBatchWithSource(location, content.length, sha256(content));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                1L,
                "Super",
                List.of("R_SUPER"),
                List.of("finance:reconciliation:source-download"));

        ResponseEntity<ByteArrayResource> response = rawController().source(principal, batchId);
        assertThat(response.getHeaders().getCacheControl())
                .contains("private", "no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_resolution_audit
                        where batch_id = :batchId and action = 'SOURCE_DOWNLOAD'
                          and operator_id = 1
                        """)
                .param("batchId", batchId)
                .query(Integer.class)
                .single()).isOne();

        try (java.io.InputStream input = response.getBody().getInputStream()) {
            assertThat(input.read()).isEqualTo(content[0] & 0xff);
        }
    }

    @Test
    void sourceWithSameSizeButDifferentSha256IsRejectedWithoutAudit() {
        byte[] expected = "private-finance-source".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = expected.clone();
        tampered[0] ^= 1;
        StorageObjectLocation location = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test-bucket",
                "ap-test",
                "private/finance/source-tampered.csv");
        storageProvider.put(
                location,
                "text/csv; charset=UTF-8",
                new ByteArrayInputStream(tampered),
                tampered.length);
        long batchId = insertBatchWithSource(location, expected.length, sha256(expected));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                1L,
                "Super",
                List.of("R_SUPER"),
                List.of("finance:reconciliation:source-download"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> rawController().source(principal, batchId))
                .isInstanceOf(org.muybaby.shopserver.common.error.BusinessException.class)
                .extracting(error -> ((org.muybaby.shopserver.common.error.BusinessException) error)
                        .errorCode())
                .isEqualTo(org.muybaby.shopserver.common.error.ErrorCode
                        .FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_resolution_audit
                        where batch_id = :batchId and action = 'SOURCE_DOWNLOAD'
                        """)
                .param("batchId", batchId)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void sourceChangedCandidateCanBeVerifiedDownloadedAndAudited() throws Exception {
        byte[] content = "candidate-finance-source".getBytes(StandardCharsets.UTF_8);
        StorageObjectLocation location = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                "finance-test-bucket",
                "ap-test",
                "private/finance/source-candidate.csv");
        storageProvider.put(
                location,
                "text/csv; charset=UTF-8",
                new ByteArrayInputStream(content),
                content.length);
        long batchId = insertBatchWithoutSource();
        long differenceId = insertCandidateDifference(
                batchId, location, content.length, sha256(content));
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                1L,
                "Super",
                List.of("R_SUPER"),
                List.of("finance:reconciliation:source-download"));

        ResponseEntity<ByteArrayResource> response =
                rawController().candidateSource(principal, differenceId);

        assertThat(response.getBody().getByteArray()).isEqualTo(content);
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(jdbcClient.sql("""
                        select count(*) from finance_reconciliation_resolution_audit
                        where batch_id = :batchId and difference_id = :differenceId
                          and action = 'SOURCE_DOWNLOAD' and operator_id = 1
                          and metadata like 'source=candidate;%'
                        """)
                .param("batchId", batchId)
                .param("differenceId", differenceId)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void workerDisabledRunFailsSafeAndConflictCodeMapsToHttp409() throws Exception {
        String token = issueAdminToken(
                jdbcClient, opaqueTokenService, List.of("finance:reconciliation:run"));
        mockMvc.perform(post("/admin/finance/reconciliation/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {"billDate":"2026-08-01"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(130001));

        org.muybaby.shopserver.common.error.GlobalExceptionHandler handler =
                new org.muybaby.shopserver.common.error.GlobalExceptionHandler();
        ResponseEntity<org.muybaby.shopserver.common.api.ApiResponse<Void>> conflict =
                handler.handleBusinessException(new org.muybaby.shopserver.common.error.BusinessException(
                        org.muybaby.shopserver.common.error.ErrorCode.FINANCE_RECONCILIATION_CONFLICT));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    }

    private long insertBatchWithoutSource() {
        return insertBatch("", null, 0L, false, "");
    }

    private AdminFinanceReconciliationController rawController() {
        return AopTestUtils.getTargetObject(controller);
    }

    private long insertBatchWithSource(
            StorageObjectLocation location,
            long size,
            String contentSha256
    ) {
        return insertBatch(location.objectKey(), location, size, true, contentSha256);
    }

    private long insertBatch(
            String objectKey,
            StorageObjectLocation location,
            long size,
            boolean verified,
            String contentSha256
    ) {
        long batchId = 9_007_199_254_741_100L + System.nanoTime() % 10_000L;
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 10, 0);
        jdbcClient.sql("""
                        insert into finance_reconciliation_batch
                            (id, mch_id, bill_date, bill_type, status, phase,
                             provider_hash_verified, content_sha256,
                             storage_provider, storage_container, storage_region,
                             object_key, content_type, source_size_bytes,
                             requested_at, created_at, updated_at)
                        values
                            (:id, :mchId, date '2026-08-01', 'TRADE_ALL', 'BALANCED', 'COMPLETE',
                             :verified, :contentSha256, :storageProvider, :storageContainer,
                             :storageRegion, :objectKey, :contentType, :sourceSize,
                             :now, :now, :now)
                        """)
                .param("id", batchId)
                .param("mchId", "mch-" + batchId)
                .param("verified", verified)
                .param("contentSha256", contentSha256)
                .param("storageProvider", location == null ? "" : location.provider().name())
                .param("storageContainer", location == null ? "" : location.container())
                .param("storageRegion", location == null ? "" : location.region())
                .param("objectKey", objectKey)
                .param("contentType", verified ? "text/csv; charset=UTF-8" : "")
                .param("sourceSize", size)
                .param("now", now)
                .update();
        return batchId;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long insertCandidateDifference(
            long batchId,
            StorageObjectLocation location,
            long size,
            String contentSha256
    ) {
        long differenceId = batchId - 100_000L;
        jdbcClient.sql("""
                        insert into finance_reconciliation_difference
                            (id, batch_id, diff_key, difference_type, severity, status,
                             provider_evidence, local_evidence,
                             candidate_content_sha256, candidate_storage_provider,
                             candidate_storage_container, candidate_storage_region,
                             candidate_object_key, candidate_size_bytes)
                        values
                            (:id, :batchId, :diffKey, 'SOURCE_CHANGED', 'CRITICAL', 'OPEN',
                             '{}', '{}', :contentSha256, :storageProvider,
                             :storageContainer, :storageRegion, :objectKey, :sourceSize)
                        """)
                .param("id", differenceId)
                .param("batchId", batchId)
                .param("diffKey", contentSha256)
                .param("contentSha256", contentSha256)
                .param("storageProvider", location.provider().name())
                .param("storageContainer", location.container())
                .param("storageRegion", location.region())
                .param("objectKey", location.objectKey())
                .param("sourceSize", size)
                .update();
        return differenceId;
    }
}
