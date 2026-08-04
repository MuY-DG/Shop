package org.muybaby.shopserver.product.engagement;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewQueryRequest;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewResponse;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewStatusRequest;
import org.muybaby.shopserver.product.engagement.dto.AdminProductReviewUpdateRequest;
import org.muybaby.shopserver.product.engagement.service.AdminProductReviewService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/product/reviews")
public class AdminProductReviewController {

    private final AdminProductReviewService reviewService;

    public AdminProductReviewController(AdminProductReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('product:review:read', 'product:review:moderate')")
    public ApiResponse<PageResult<AdminProductReviewResponse>> page(
            @Valid AdminProductReviewQueryRequest query
    ) {
        return ApiResponse.success(reviewService.page(query));
    }

    @PutMapping("/{reviewId}/status")
    @PreAuthorize("hasAuthority('product:review:moderate')")
    public ApiResponse<Void> updateStatus(
            @PathVariable Long reviewId,
            @Valid @RequestBody AdminProductReviewStatusRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        reviewService.updateStatus(reviewId, request.status(), principal.subjectId());
        return ApiResponse.success();
    }

    @PutMapping("/{reviewId}")
    @PreAuthorize("hasAuthority('product:review:moderate')")
    public ApiResponse<Void> update(
            @PathVariable Long reviewId,
            @Valid @RequestBody AdminProductReviewUpdateRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        reviewService.update(reviewId, request, principal.subjectId());
        return ApiResponse.success();
    }

    @DeleteMapping("/{reviewId}")
    @PreAuthorize("hasAuthority('product:review:moderate')")
    public ApiResponse<Void> delete(@PathVariable Long reviewId) {
        reviewService.delete(reviewId);
        return ApiResponse.success();
    }
}
