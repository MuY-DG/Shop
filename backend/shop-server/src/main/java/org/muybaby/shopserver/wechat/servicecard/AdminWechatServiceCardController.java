package org.muybaby.shopserver.wechat.servicecard;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryQuery;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryResponse;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardStatusResponse;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardRuntimeUpdateRequest;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/wechat-service-cards")
public class AdminWechatServiceCardController {

    private final WechatServiceCardAdminReadService readService;
    private final WechatServiceCardAdminRuntimeService adminRuntimeService;

    public AdminWechatServiceCardController(
            WechatServiceCardAdminReadService readService,
            WechatServiceCardAdminRuntimeService adminRuntimeService
    ) {
        this.readService = readService;
        this.adminRuntimeService = adminRuntimeService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('wechat-service-card:read')")
    public ApiResponse<AdminWechatServiceCardStatusResponse> status() {
        return ApiResponse.success(readService.status());
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('wechat-service-card:read')")
    public ApiResponse<PageResult<AdminWechatServiceCardDeliveryResponse>> deliveries(
            @Valid AdminWechatServiceCardDeliveryQuery query
    ) {
        return ApiResponse.success(readService.deliveries(query));
    }

    @PutMapping("/runtime")
    @PreAuthorize("hasAuthority('wechat-service-card:runtime:write')")
    public ApiResponse<AdminWechatServiceCardStatusResponse> updateRuntime(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminWechatServiceCardRuntimeUpdateRequest request
    ) {
        return ApiResponse.success(adminRuntimeService.update(
                request, principal.subjectId()
        ));
    }
}
