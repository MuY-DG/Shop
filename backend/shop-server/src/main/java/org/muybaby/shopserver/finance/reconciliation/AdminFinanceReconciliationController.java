package org.muybaby.shopserver.finance.reconciliation;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchDetailResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationBatchResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationDifferenceResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationEntryQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationExportQuery;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationInvestigateRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationResolutionAuditResponse;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationResolveRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationRetryRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminReconciliationRunRequest;
import org.muybaby.shopserver.finance.reconciliation.dto.AdminTradeBillEntryResponse;
import org.muybaby.shopserver.finance.reconciliation.service.FinanceReconciliationCommandService;
import org.muybaby.shopserver.finance.reconciliation.service.FinanceReconciliationExportService;
import org.muybaby.shopserver.finance.reconciliation.service.FinanceReconciliationReadService;
import org.muybaby.shopserver.finance.reconciliation.service.FinanceReconciliationReadService.StoredBatchSource;
import org.muybaby.shopserver.finance.reconciliation.service.FinanceReconciliationReadService.StoredCandidateSource;
import org.muybaby.shopserver.finance.reconciliation.storage.FinanceTradeBillStorage;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/admin/finance/reconciliation")
public class AdminFinanceReconciliationController {

    private final FinanceReconciliationReadService readService;
    private final FinanceReconciliationCommandService commandService;
    private final FinanceReconciliationExportService exportService;
    private final FinanceTradeBillStorage storage;
    private final FinanceReconciliationProperties properties;

    public AdminFinanceReconciliationController(
            FinanceReconciliationReadService readService,
            FinanceReconciliationCommandService commandService,
            FinanceReconciliationExportService exportService,
            FinanceTradeBillStorage storage,
            FinanceReconciliationProperties properties
    ) {
        this.readService = readService;
        this.commandService = commandService;
        this.exportService = exportService;
        this.storage = storage;
        this.properties = properties;
    }

    @GetMapping("/batches")
    @PreAuthorize("hasAuthority('finance:reconciliation:read')")
    public ApiResponse<PageResult<AdminReconciliationBatchResponse>> batches(
            @Valid AdminReconciliationBatchQuery query
    ) {
        return ApiResponse.success(readService.batches(query));
    }

    @GetMapping("/batches/{batchId}")
    @PreAuthorize("hasAuthority('finance:reconciliation:read')")
    public ApiResponse<AdminReconciliationBatchDetailResponse> batch(
            @PathVariable Long batchId
    ) {
        return ApiResponse.success(readService.batchDetail(batchId));
    }

    @GetMapping("/batches/{batchId}/entries")
    @PreAuthorize("hasAuthority('finance:reconciliation:read')")
    public ApiResponse<PageResult<AdminTradeBillEntryResponse>> entries(
            @PathVariable Long batchId,
            @Valid AdminReconciliationEntryQuery query
    ) {
        return ApiResponse.success(readService.entries(batchId, query));
    }

    @GetMapping("/batches/{batchId}/differences")
    @PreAuthorize("hasAuthority('finance:reconciliation:read')")
    public ApiResponse<PageResult<AdminReconciliationDifferenceResponse>> differences(
            @PathVariable Long batchId,
            @Valid AdminReconciliationDifferenceQuery query
    ) {
        return ApiResponse.success(readService.differences(batchId, query));
    }

    @GetMapping("/differences/{differenceId}/audits")
    @PreAuthorize("hasAuthority('finance:reconciliation:read')")
    public ApiResponse<List<AdminReconciliationResolutionAuditResponse>> audits(
            @PathVariable Long differenceId
    ) {
        return ApiResponse.success(readService.audits(differenceId));
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAuthority('finance:reconciliation:run')")
    public ApiResponse<List<AdminReconciliationBatchResponse>> run(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminReconciliationRunRequest request
    ) {
        return ApiResponse.success(commandService.requestRuns(request, principal.subjectId()));
    }

    @PostMapping("/batches/{batchId}/retry")
    @PreAuthorize("hasAuthority('finance:reconciliation:run')")
    public ApiResponse<AdminReconciliationBatchResponse> retry(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long batchId,
            @Valid @RequestBody AdminReconciliationRetryRequest request
    ) {
        return ApiResponse.success(commandService.retry(batchId, request, principal.subjectId()));
    }

    @PostMapping("/differences/{differenceId}/investigate")
    @PreAuthorize("hasAuthority('finance:reconciliation:resolve')")
    public ApiResponse<AdminReconciliationDifferenceResponse> investigate(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long differenceId,
            @Valid @RequestBody AdminReconciliationInvestigateRequest request
    ) {
        return ApiResponse.success(commandService.investigate(
                differenceId, request, principal.subjectId()));
    }

    @PostMapping("/differences/{differenceId}/resolve")
    @PreAuthorize("hasAuthority('finance:reconciliation:resolve')")
    public ApiResponse<AdminReconciliationDifferenceResponse> resolve(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long differenceId,
            @Valid @RequestBody AdminReconciliationResolveRequest request
    ) {
        return ApiResponse.success(commandService.resolve(
                differenceId, request, principal.subjectId()));
    }

    @GetMapping("/batches/{batchId}/source")
    @PreAuthorize("hasAuthority('finance:reconciliation:source-download')")
    public ResponseEntity<ByteArrayResource> source(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long batchId
    ) {
        StoredBatchSource source = readService.source(batchId);
        return downloadSource(
                principal,
                source.batchId(),
                null,
                source.location(),
                source.sizeBytes(),
                source.contentSha256(),
                "wechat-trade-bill-" + source.billDate() + ".csv"
        );
    }

    @GetMapping("/differences/{differenceId}/candidate-source")
    @PreAuthorize("hasAuthority('finance:reconciliation:source-download')")
    public ResponseEntity<ByteArrayResource> candidateSource(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long differenceId
    ) {
        StoredCandidateSource source = readService.candidateSource(differenceId);
        return downloadSource(
                principal,
                source.batchId(),
                source.differenceId(),
                source.location(),
                source.sizeBytes(),
                source.contentSha256(),
                "wechat-trade-bill-candidate-" + source.billDate() + ".csv"
        );
    }

    private ResponseEntity<ByteArrayResource> downloadSource(
            AuthenticatedPrincipal principal,
            long batchId,
            Long differenceId,
            org.muybaby.shopserver.storage.provider.StorageObjectLocation location,
            long expectedBytes,
            String contentSha256,
            String fileName
    ) {
        try {
            StoredObject stored = storage.open(location);
            if (stored.sizeBytes() != expectedBytes) {
                closeQuietly(stored.inputStream());
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
            }
            byte[] bytes = readAndVerifySource(
                    stored.inputStream(), expectedBytes, contentSha256);
            if (differenceId == null) {
                commandService.auditSourceDownload(
                        batchId, principal.subjectId(), expectedBytes);
            } else {
                commandService.auditCandidateSourceDownload(
                        batchId, differenceId, principal.subjectId(), expectedBytes);
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(fileName, StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'")
                    .contentType(MediaType.parseMediaType(FinanceTradeBillStorage.CONTENT_TYPE))
                    .contentLength(expectedBytes)
                    .body(new ByteArrayResource(bytes));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
        }
    }

    @GetMapping("/export.csv")
    @PreAuthorize("hasAuthority('finance:export')")
    public ResponseEntity<ByteArrayResource> export(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid AdminReconciliationExportQuery query
    ) {
        FinanceReconciliationExportService.ExportedCsv exported = exportService.export(query);
        byte[] bytes = exported.bytes();
        commandService.auditExport(
                principal.subjectId(), exported.filter(), exported.recordCount(), bytes.length);
        String fileName = "wechat-reconciliation-" + query.from() + "-" + query.to() + ".csv";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    private void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // A failed close must not expose or serve a source whose stored size changed.
        }
    }

    private byte[] readAndVerifySource(
            InputStream input,
            long expectedBytes,
            String contentSha256
    ) {
        long hardLimit = properties.maxSourceSize().toBytes();
        if (expectedBytes < 0 || expectedBytes > hardLimit || expectedBytes > Integer.MAX_VALUE) {
            closeQuietly(input);
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
        }
        try (InputStream sourceInput = input;
             java.io.ByteArrayOutputStream output =
                     new java.io.ByteArrayOutputStream((int) expectedBytes)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int read;
            while ((read = sourceInput.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > expectedBytes || total > hardLimit) {
                    throw new BusinessException(
                            ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            if (total != expectedBytes || !matchesSha256(contentSha256, digest.digest())) {
                throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
            }
            return output.toByteArray();
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException ex) {
            throw new BusinessException(ErrorCode.FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE);
        }
    }

    private boolean matchesSha256(String expectedHex, byte[] actual) {
        try {
            byte[] expected = HexFormat.of().parseHex(expectedHex == null ? "" : expectedHex);
            return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

}
