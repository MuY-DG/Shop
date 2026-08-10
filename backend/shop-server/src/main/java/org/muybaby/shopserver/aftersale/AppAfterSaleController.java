package org.muybaby.shopserver.aftersale;

import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEligibilityResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleQuoteResponse;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleQuoteRequest;
import org.muybaby.shopserver.aftersale.dto.AppReturnShipmentRequest;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleV2Service;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
public class AppAfterSaleController {

    private final AppAfterSaleService appAfterSaleService;
    private final AppAfterSaleV2Service appAfterSaleV2Service;

    public AppAfterSaleController(
            AppAfterSaleService appAfterSaleService,
            AppAfterSaleV2Service appAfterSaleV2Service
    ) {
        this.appAfterSaleService = appAfterSaleService;
        this.appAfterSaleV2Service = appAfterSaleV2Service;
    }

    @PostMapping("/app/orders/{orderId}/after-sales")
    public ApiResponse<AfterSaleResponse> apply(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @RequestBody AppAfterSaleApplyRequest request
    ) {
        return ApiResponse.success(appAfterSaleV2Service.apply(principal, orderId, request));
    }

    @GetMapping("/app/orders/{orderId}/after-sales/eligibility")
    public ApiResponse<AfterSaleEligibilityResponse> eligibility(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appAfterSaleV2Service.eligibility(principal, orderId));
    }

    @PostMapping("/app/orders/{orderId}/after-sales/quote")
    public ApiResponse<AfterSaleQuoteResponse> quote(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @RequestBody AppAfterSaleQuoteRequest request
    ) {
        return ApiResponse.success(appAfterSaleV2Service.quote(principal, orderId, request));
    }

    @PostMapping("/app/after-sales/{afterSaleId}/cancel")
    public ApiResponse<AfterSaleResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId
    ) {
        return ApiResponse.success(appAfterSaleV2Service.cancel(principal, afterSaleId));
    }

    @PutMapping("/app/after-sales/{afterSaleId}/return-shipment")
    public ApiResponse<AfterSaleResponse> submitReturnShipment(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId,
            @RequestBody AppReturnShipmentRequest request
    ) {
        return ApiResponse.success(
                appAfterSaleV2Service.submitReturnShipment(principal, afterSaleId, request));
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
