package org.muybaby.shopserver.logistics;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.logistics.dto.WechatDeliveryCompanyResponse;
import org.muybaby.shopserver.logistics.dto.WechatShippingCapabilityResponse;
import org.muybaby.shopserver.logistics.service.WechatShippingCatalogService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/wechat-shipping")
public class AdminWechatShippingController {

    private final WechatShippingCatalogService catalogService;

    public AdminWechatShippingController(WechatShippingCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/capability")
    @PreAuthorize("hasAuthority('order:ship')")
    public ApiResponse<WechatShippingCapabilityResponse> capability(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(catalogService.capability(principal));
    }

    @GetMapping("/carriers")
    @PreAuthorize("hasAuthority('order:ship')")
    public ApiResponse<List<WechatDeliveryCompanyResponse>> carriers(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(catalogService.list(principal));
    }

    @PostMapping("/carriers/sync")
    @PreAuthorize("hasAuthority('order:ship')")
    public ApiResponse<List<WechatDeliveryCompanyResponse>> syncCarriers(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(catalogService.sync(principal));
    }
}
