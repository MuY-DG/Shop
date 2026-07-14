package org.muybaby.shopserver.coupon;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateResponse;
import org.muybaby.shopserver.coupon.dto.ProductCouponBindingRequest;
import org.muybaby.shopserver.coupon.service.ProductCouponService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/product/spus/{spuId}/coupons")
public class AdminProductCouponController {

    private static final String READ_AUTHORITIES = "hasAnyAuthority(" +
            "'product:spu:create'," +
            "'product:spu:update'," +
            "'product:coupon:bind'," +
            "'product:coupon:create')";

    private final ProductCouponService productCouponService;

    public AdminProductCouponController(ProductCouponService productCouponService) {
        this.productCouponService = productCouponService;
    }

    @GetMapping
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<List<AdminCouponTemplateResponse>> list(@PathVariable Long spuId) {
        return ApiResponse.success(productCouponService.adminCoupons(spuId));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('product:coupon:bind')")
    public ApiResponse<Void> replaceBindings(
            @PathVariable Long spuId,
            @RequestBody ProductCouponBindingRequest request
    ) {
        productCouponService.replaceBindings(spuId, request);
        return ApiResponse.success();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:coupon:create')")
    public ApiResponse<Long> create(
            @PathVariable Long spuId,
            @RequestBody AdminCouponTemplateRequest request
    ) {
        return ApiResponse.success(productCouponService.createProductCoupon(spuId, request));
    }
}
