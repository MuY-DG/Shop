package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureRequest;
import org.muybaby.shopserver.product.dto.ProductFoodDisclosureResponse;
import org.muybaby.shopserver.product.service.ProductFoodComplianceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/product/spus/{spuId}/food-disclosure")
public class AdminProductFoodComplianceController {

    private final ProductFoodComplianceService productFoodComplianceService;

    public AdminProductFoodComplianceController(ProductFoodComplianceService productFoodComplianceService) {
        this.productFoodComplianceService = productFoodComplianceService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('product:spu:create', 'product:spu:update', 'product:spu:publish')")
    public ApiResponse<ProductFoodDisclosureResponse> get(@PathVariable Long spuId) {
        return ApiResponse.success(productFoodComplianceService.get(spuId));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('product:spu:update')")
    public ApiResponse<ProductFoodDisclosureResponse> update(
            @PathVariable Long spuId,
            @Valid @RequestBody ProductFoodDisclosureRequest request
    ) {
        return ApiResponse.success(productFoodComplianceService.update(spuId, request));
    }
}
