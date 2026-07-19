package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.dto.AdminLowStockThresholdRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/product/skus")
public class AdminProductSkuController {

    private final AdminProductService adminProductService;

    public AdminProductSkuController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    @PostMapping("/{skuId}/stock-adjustments")
    @PreAuthorize("hasAuthority('product:sku:stock')")
    public ApiResponse<Void> adjustStock(
            @PathVariable Long skuId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminStockAdjustmentRequest request
    ) {
        adminProductService.adjustSkuStock(skuId, request, principal.subjectId());
        return ApiResponse.success();
    }

    @PutMapping("/{skuId}/low-stock-threshold")
    @PreAuthorize("hasAuthority('product:sku:stock')")
    public ApiResponse<Void> updateLowStockThreshold(
            @PathVariable Long skuId,
            @Valid @RequestBody AdminLowStockThresholdRequest request
    ) {
        adminProductService.updateSkuLowStockThreshold(skuId, request.lowStockThreshold());
        return ApiResponse.success();
    }
}
