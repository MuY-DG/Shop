package org.muybaby.shopserver.coupon;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.coupon.dto.AppClaimableCouponResponse;
import org.muybaby.shopserver.coupon.dto.AppUserCouponResponse;
import org.muybaby.shopserver.coupon.dto.AvailableCouponRequest;
import org.muybaby.shopserver.coupon.dto.AvailableCouponResponse;
import org.muybaby.shopserver.coupon.service.AppCouponService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/coupons")
public class AppCouponController {

    private final AppCouponService appCouponService;

    public AppCouponController(AppCouponService appCouponService) {
        this.appCouponService = appCouponService;
    }

    @GetMapping("/claimable")
    public ApiResponse<List<AppClaimableCouponResponse>> claimable(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(appCouponService.claimable(principal));
    }

    @PostMapping("/templates/{templateId}/claim")
    public ApiResponse<AppUserCouponResponse> claim(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long templateId
    ) {
        return ApiResponse.success(appCouponService.claim(principal, templateId));
    }

    @GetMapping("/mine")
    public ApiResponse<List<AppUserCouponResponse>> mine(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            String status
    ) {
        return ApiResponse.success(appCouponService.mine(principal, status));
    }

    @PostMapping("/available")
    public ApiResponse<AvailableCouponResponse> available(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody(required = false) AvailableCouponRequest request
    ) {
        return ApiResponse.success(appCouponService.available(principal, request));
    }
}
