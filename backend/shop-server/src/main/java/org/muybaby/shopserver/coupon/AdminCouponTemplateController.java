package org.muybaby.shopserver.coupon;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateQueryRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateRequest;
import org.muybaby.shopserver.coupon.dto.AdminCouponTemplateResponse;
import org.muybaby.shopserver.coupon.service.AdminCouponService;
import org.muybaby.shopserver.coupon.service.CouponReadMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/marketing/coupons/templates")
public class AdminCouponTemplateController {

    private final AdminCouponService adminCouponService;
    private final CouponReadMapper couponReadMapper;

    public AdminCouponTemplateController(
            AdminCouponService adminCouponService,
            CouponReadMapper couponReadMapper
    ) {
        this.adminCouponService = adminCouponService;
        this.couponReadMapper = couponReadMapper;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminCouponTemplateResponse>> page(AdminCouponTemplateQueryRequest query) {
        return ApiResponse.success(couponReadMapper.adminTemplatePage(query));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AdminCouponTemplateRequest request) {
        return ApiResponse.success(adminCouponService.create(request));
    }

    @PutMapping("/{templateId}")
    public ApiResponse<Void> update(@PathVariable Long templateId, @Valid @RequestBody AdminCouponTemplateRequest request) {
        adminCouponService.update(templateId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{templateId}/enable")
    public ApiResponse<Void> enable(@PathVariable Long templateId) {
        adminCouponService.enable(templateId);
        return ApiResponse.success();
    }

    @PostMapping("/{templateId}/disable")
    public ApiResponse<Void> disable(@PathVariable Long templateId) {
        adminCouponService.disable(templateId);
        return ApiResponse.success();
    }
}
