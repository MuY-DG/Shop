package org.muybaby.shopserver.user;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.dto.AdminCouponIssueRequest;
import org.muybaby.shopserver.user.dto.AdminCouponIssueResponse;
import org.muybaby.shopserver.user.dto.AdminCustomerQueryRequest;
import org.muybaby.shopserver.user.dto.AdminCustomerResponse;
import org.muybaby.shopserver.user.dto.AdminDirectCouponIssueRequest;
import org.muybaby.shopserver.user.dto.AdminIssuableCouponTemplateResponse;
import org.muybaby.shopserver.user.service.AdminCustomerService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private final AdminCustomerService adminCustomerService;

    public AdminCustomerController(AdminCustomerService adminCustomerService) {
        this.adminCustomerService = adminCustomerService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('customer:user:read', 'customer:coupon:issue')")
    public ApiResponse<PageResult<AdminCustomerResponse>> page(AdminCustomerQueryRequest query) {
        return ApiResponse.success(adminCustomerService.page(query));
    }

    @GetMapping("/{userId}/issuable-coupon-templates")
    @PreAuthorize("hasAuthority('customer:coupon:issue')")
    public ApiResponse<List<AdminIssuableCouponTemplateResponse>> issuableCouponTemplates(
            @PathVariable Long userId
    ) {
        return ApiResponse.success(adminCustomerService.issuableCouponTemplates(userId));
    }

    @PostMapping("/{userId}/coupons")
    @PreAuthorize("hasAuthority('customer:coupon:issue')")
    public ApiResponse<AdminCouponIssueResponse> issueCoupon(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminCouponIssueRequest request
    ) {
        return ApiResponse.success(adminCustomerService.issueCoupon(principal.subjectId(), userId, request));
    }

    @PostMapping("/{userId}/direct-coupons")
    @PreAuthorize("hasAuthority('customer:coupon:issue')")
    public ApiResponse<AdminCouponIssueResponse> createDirectCoupon(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminDirectCouponIssueRequest request
    ) {
        return ApiResponse.success(
                adminCustomerService.createDirectCoupon(principal.subjectId(), userId, request)
        );
    }
}
