package org.muybaby.shopserver.coupon;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.coupon.dto.AppClaimableCouponResponse;
import org.muybaby.shopserver.coupon.service.AppCouponService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/product/spus/{spuId}/coupons")
public class AppProductCouponController {

    private final AppCouponService appCouponService;

    public AppProductCouponController(AppCouponService appCouponService) {
        this.appCouponService = appCouponService;
    }

    @GetMapping
    public ApiResponse<List<AppClaimableCouponResponse>> claimable(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        return ApiResponse.success(appCouponService.claimableForProduct(principal, spuId));
    }
}
