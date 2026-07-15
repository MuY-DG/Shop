package org.muybaby.shopserver.coupon;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.coupon.dto.AdminCouponClaimQueryRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponClaimResponse;
import org.muybaby.shopserver.coupon.service.CouponReadMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/marketing/coupons/claims")
public class AdminCouponClaimController {

    private final CouponReadMapper couponReadMapper;

    public AdminCouponClaimController(CouponReadMapper couponReadMapper) {
        this.couponReadMapper = couponReadMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('coupon:claim:read')")
    public ApiResponse<PageResult<AdminCouponClaimResponse>> page(AdminCouponClaimQueryRequest query) {
        return ApiResponse.success(couponReadMapper.adminClaimPage(query));
    }
}
