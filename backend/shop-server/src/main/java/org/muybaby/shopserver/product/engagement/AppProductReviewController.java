package org.muybaby.shopserver.product.engagement;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.engagement.dto.ProductEngagementPageRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewEligibilityResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewPageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductReviewUpdateRequest;
import org.muybaby.shopserver.product.engagement.service.AppProductReviewService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
            ProductEngagementPageRequest request
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

    @GetMapping("/reviews/mine")
    public ApiResponse<PageResult<ProductReviewResponse>> mine(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            ProductEngagementPageRequest request
    ) {
        return ApiResponse.success(reviewService.mine(principal, request));
    }

    @PutMapping("/reviews/{reviewId}")
    public ApiResponse<ProductReviewResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long reviewId,
            @Valid @RequestBody ProductReviewUpdateRequest request
    ) {
        return ApiResponse.success(reviewService.update(principal, reviewId, request));
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long reviewId
    ) {
        reviewService.delete(principal, reviewId);
        return ApiResponse.success();
    }
}
