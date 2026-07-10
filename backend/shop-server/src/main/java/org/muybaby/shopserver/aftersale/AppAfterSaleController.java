package org.muybaby.shopserver.aftersale;

import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AppAfterSaleController {

    private final AppAfterSaleService appAfterSaleService;

    public AppAfterSaleController(AppAfterSaleService appAfterSaleService) {
        this.appAfterSaleService = appAfterSaleService;
    }

    @PostMapping("/app/orders/{orderId}/after-sales")
    public ApiResponse<AfterSaleResponse> apply(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId,
            @RequestBody AppAfterSaleApplyRequest request
    ) {
        return ApiResponse.success(appAfterSaleService.apply(principal, orderId, request));
    }

    @GetMapping("/app/orders/{orderId}/after-sales")
    public ApiResponse<List<AfterSaleResponse>> listForOrder(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(appAfterSaleService.listForOrder(principal, orderId));
    }

    @GetMapping("/app/after-sales/{afterSaleId}")
    public ApiResponse<AfterSaleResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId
    ) {
        return ApiResponse.success(appAfterSaleService.detail(principal, afterSaleId));
    }

    @GetMapping("/app/after-sales")
    public ApiResponse<PageResult<AfterSaleResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status
    ) {
        return ApiResponse.success(appAfterSaleService.list(principal, current, size, status));
    }
}
