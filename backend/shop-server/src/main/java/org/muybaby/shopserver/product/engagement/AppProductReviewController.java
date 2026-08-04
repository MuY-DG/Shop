package org.muybaby.shopserver.product.engagement;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewEligibilityResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewPageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewPageRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewResponse;
import org.muybaby.shopserver.product.engagement.service.AppProductReviewService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/app/product")
public class AppProductReviewController {

    private final AppProductReviewService reviewService;

    public AppProductReviewController(AppProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/spus/{spuId}/reviews")
    public ApiResponse<ProductReviewPageResponse> page(
            @PathVariable Long spuId,
            @Valid ProductReviewPageRequest request
    ) {
        return ApiResponse.success(reviewService.page(spuId, request));
    }

    @GetMapping("/spus/{spuId}/review-eligibility")
    public ApiResponse<ProductReviewEligibilityResponse> eligibility(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        return ApiResponse.success(reviewService.eligibility(principal, spuId));
    }

    @PostMapping("/spus/{spuId}/reviews")
    public ApiResponse<ProductReviewResponse> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId,
            @Valid @RequestBody ProductReviewRequest request
    ) {
        return ApiResponse.success(reviewService.create(principal, spuId, request));
    }

    @PostMapping("/order-items/{orderItemId}/review-images")
    public ApiResponse<StorageAssetResponse> uploadImage(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderItemId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(reviewService.uploadImage(principal, orderItemId, file));
    }

    @PostMapping("/order-items/{orderItemId}/review-images/upload-sessions")
    public ApiResponse<DirectUploadSessionResponse> createImageUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderItemId,
            @Valid @RequestBody DirectUploadSessionRequest request
    ) {
        return ApiResponse.success(
                reviewService.createImageUploadSession(principal, orderItemId, request));
    }

    @PostMapping("/order-items/{orderItemId}/review-images/upload-sessions/{uploadId}/complete")
    public ApiResponse<StorageAssetResponse> completeImageUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderItemId,
            @PathVariable String uploadId
    ) {
        return ApiResponse.success(
                reviewService.completeImageUploadSession(principal, orderItemId, uploadId));
    }

    @DeleteMapping("/order-items/{orderItemId}/review-images/upload-sessions/{uploadId}")
    public ApiResponse<Void> cancelImageUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderItemId,
            @PathVariable String uploadId
    ) {
        reviewService.cancelImageUploadSession(principal, orderItemId, uploadId);
        return ApiResponse.success();
    }

}
