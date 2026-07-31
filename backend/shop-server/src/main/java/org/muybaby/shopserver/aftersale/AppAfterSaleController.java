package org.muybaby.shopserver.aftersale;

import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class AppAfterSaleController {

    private final AppAfterSaleService appAfterSaleService;

    public AppAfterSaleController(AppAfterSaleService appAfterSaleService) {
        this.appAfterSaleService = appAfterSaleService;
    }

    @PostMapping("/app/orders/{orderId}/after-sales")
    public ApiResponse<AfterSaleResponse> apply(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @RequestBody AppAfterSaleApplyRequest request
    ) {
        return ApiResponse.success(appAfterSaleService.apply(principal, orderId, request));
    }

    @PostMapping("/app/orders/{orderId}/after-sale-evidence")
    public ApiResponse<StorageAssetResponse> uploadEvidence(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(appAfterSaleService.uploadEvidence(principal, orderId, file));
    }

    @PostMapping("/app/orders/{orderId}/after-sale-evidence/upload-sessions")
    public ApiResponse<DirectUploadSessionResponse> createEvidenceUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @Valid @RequestBody DirectUploadSessionRequest request
    ) {
        return ApiResponse.success(
                appAfterSaleService.createEvidenceUploadSession(
                        principal, orderId, request));
    }

    @PostMapping("/app/orders/{orderId}/after-sale-evidence/upload-sessions/{uploadId}/complete")
    public ApiResponse<StorageAssetResponse> completeEvidenceUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable String uploadId
    ) {
        return ApiResponse.success(
                appAfterSaleService.completeEvidenceUploadSession(
                        principal, orderId, uploadId));
    }

    @DeleteMapping("/app/orders/{orderId}/after-sale-evidence/upload-sessions/{uploadId}")
    public ApiResponse<Void> cancelEvidenceUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @PathVariable String uploadId
    ) {
        appAfterSaleService.cancelEvidenceUploadSession(
                principal, orderId, uploadId);
        return ApiResponse.success();
    }

    @GetMapping("/app/orders/{orderId}/after-sales")
    public ApiResponse<List<AfterSaleResponse>> listForOrder(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appAfterSaleService.listForOrder(principal, orderId));
    }

    @GetMapping("/app/after-sales/{afterSaleId}")
    public ApiResponse<AfterSaleResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId
    ) {
        return ApiResponse.success(appAfterSaleService.detail(principal, afterSaleId));
    }

    @GetMapping("/app/after-sales")
    public ApiResponse<PageResult<AfterSaleResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status
    ) {
        return ApiResponse.success(appAfterSaleService.list(principal, current, size, status));
    }
}
