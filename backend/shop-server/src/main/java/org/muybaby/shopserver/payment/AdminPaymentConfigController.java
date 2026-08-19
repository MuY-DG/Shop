package org.muybaby.shopserver.payment;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigRequest;
import org.muybaby.shopserver.payment.dto.AdminPaymentConfigResponse;
import org.muybaby.shopserver.payment.dto.EffectivePaymentConfigStateResponse;
import org.muybaby.shopserver.payment.service.AdminPaymentConfigService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/pay/configs")
public class AdminPaymentConfigController {

    private final AdminPaymentConfigService adminPaymentConfigService;

    public AdminPaymentConfigController(AdminPaymentConfigService adminPaymentConfigService) {
        this.adminPaymentConfigService = adminPaymentConfigService;
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAuthority('payment:config:read')")
    public ApiResponse<EffectivePaymentConfigStateResponse> effective() {
        return ApiResponse.success(adminPaymentConfigService.effective());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payment:config:read')")
    public ApiResponse<PageResult<AdminPaymentConfigResponse>> page(
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size
    ) {
        return ApiResponse.success(adminPaymentConfigService.page(current, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('payment:config:write')")
    public ApiResponse<AdminPaymentConfigResponse> create(@RequestBody AdminPaymentConfigRequest request) {
        return ApiResponse.success(adminPaymentConfigService.create(request));
    }

    @PutMapping("/{configId}")
    @PreAuthorize("hasAuthority('payment:config:write')")
    public ApiResponse<AdminPaymentConfigResponse> update(
            @PathVariable Long configId,
            @RequestBody AdminPaymentConfigRequest request
    ) {
        return ApiResponse.success(adminPaymentConfigService.update(configId, request));
    }

    @PostMapping("/{configId}/import-legacy-secret-files")
    @PreAuthorize("hasAuthority('payment:config:write')")
    public ApiResponse<AdminPaymentConfigResponse> importLegacySecretFiles(@PathVariable Long configId) {
        return ApiResponse.success(adminPaymentConfigService.importLegacySecretFiles(configId));
    }

    @PostMapping("/{configId}/enable")
    @PreAuthorize("hasAuthority('payment:config:enable')")
    public ApiResponse<AdminPaymentConfigResponse> enable(@PathVariable Long configId) {
        return ApiResponse.success(adminPaymentConfigService.enable(configId));
    }

    @DeleteMapping("/{configId}")
    @PreAuthorize("hasAuthority('payment:config:delete')")
    public ApiResponse<Void> delete(
            @PathVariable Long configId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        adminPaymentConfigService.delete(configId, principal.subjectId());
        return ApiResponse.success();
    }
}
