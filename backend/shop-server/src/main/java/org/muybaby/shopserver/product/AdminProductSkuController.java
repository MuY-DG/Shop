package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ApiResponse<Void> adjustStock(
            @PathVariable Long skuId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminStockAdjustmentRequest request
    ) {
        adminProductService.adjustSkuStock(skuId, request, principal.subjectId());
        return ApiResponse.success();
    }
}
