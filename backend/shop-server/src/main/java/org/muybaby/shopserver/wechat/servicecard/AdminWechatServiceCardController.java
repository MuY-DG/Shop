package org.muybaby.shopserver.wechat.servicecard;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryQuery;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardDeliveryResponse;
import org.muybaby.shopserver.wechat.servicecard.dto.AdminWechatServiceCardStatusResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/wechat-service-cards")
public class AdminWechatServiceCardController {

    private final WechatServiceCardAdminReadService readService;

    public AdminWechatServiceCardController(WechatServiceCardAdminReadService readService) {
        this.readService = readService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<AdminWechatServiceCardStatusResponse> status() {
        return ApiResponse.success(readService.status());
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<PageResult<AdminWechatServiceCardDeliveryResponse>> deliveries(
            @Valid AdminWechatServiceCardDeliveryQuery query
    ) {
        return ApiResponse.success(readService.deliveries(query));
    }
}
