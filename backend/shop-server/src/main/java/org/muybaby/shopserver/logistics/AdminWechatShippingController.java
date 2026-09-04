package org.muybaby.shopserver.logistics;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.logistics.dto.AdminWechatShippingRuntimeResponse;
import org.muybaby.shopserver.logistics.dto.AdminWechatShippingRuntimeUpdateRequest;
import org.muybaby.shopserver.logistics.dto.WechatDeliveryCompanyResponse;
import org.muybaby.shopserver.logistics.dto.WechatShippingCapabilityResponse;
import org.muybaby.shopserver.logistics.service.WechatShippingCatalogService;
import org.muybaby.shopserver.logistics.service.WechatShippingRuntimeSettingService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/wechat-shipping")
public class AdminWechatShippingController {

    private final WechatShippingCatalogService catalogService;
    private final WechatShippingRuntimeSettingService runtimeSettingService;

    public AdminWechatShippingController(
            WechatShippingCatalogService catalogService,
            WechatShippingRuntimeSettingService runtimeSettingService
    ) {
        this.catalogService = catalogService;
        this.runtimeSettingService = runtimeSettingService;
    }

    @GetMapping("/runtime")
    @PreAuthorize("hasAuthority('wechat-shipping:runtime:read')")
    public ApiResponse<AdminWechatShippingRuntimeResponse> runtime() {
        return ApiResponse.success(toRuntimeResponse(runtimeSettingService.current()));
    }

    @PutMapping("/runtime")
    @PreAuthorize("hasAuthority('wechat-shipping:runtime:write')")
    public ApiResponse<AdminWechatShippingRuntimeResponse> updateRuntime(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminWechatShippingRuntimeUpdateRequest request
    ) {
        return ApiResponse.success(toRuntimeResponse(
                runtimeSettingService.update(request, principal.subjectId())
        ));
    }

    @GetMapping("/capability")
    @PreAuthorize("hasAnyAuthority('order:ship', 'wechat-shipping:runtime:read')")
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

    private AdminWechatShippingRuntimeResponse toRuntimeResponse(
            WechatShippingRuntimeSettingService.RuntimeSetting setting
    ) {
        return new AdminWechatShippingRuntimeResponse(
                setting.uploadEnabled(),
                setting.deliveryEnabled(),
                setting.receiptReconciliationEnabled(),
                setting.persisted(),
                setting.version(),
                setting.defaultUploadEnabled(),
                setting.defaultDeliveryEnabled(),
                setting.defaultReceiptReconciliationEnabled(),
                setting.reason(),
                setting.updatedBy(),
                setting.updatedAt()
        );
    }
}
