package org.muybaby.shopserver.aftersale;

import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleAuditRequest;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleQueryRequest;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleStatusCountsResponse;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleSummaryResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.service.AdminAfterSaleService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/after-sales")
public class AdminAfterSaleController {

    private final AdminAfterSaleService adminAfterSaleService;

    public AdminAfterSaleController(AdminAfterSaleService adminAfterSaleService) {
        this.adminAfterSaleService = adminAfterSaleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('aftersale:read')")
    public ApiResponse<PageResult<AdminAfterSaleSummaryResponse>> page(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            AdminAfterSaleQueryRequest query
    ) {
        return ApiResponse.success(adminAfterSaleService.page(principal, query));
    }

    @GetMapping("/status-counts")
    @PreAuthorize("hasAuthority('aftersale:read')")
    public ApiResponse<AdminAfterSaleStatusCountsResponse> statusCounts(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            AdminAfterSaleQueryRequest query
    ) {
        return ApiResponse.success(adminAfterSaleService.statusCounts(principal, query));
    }

    @GetMapping("/{afterSaleId}")
    @PreAuthorize("hasAuthority('aftersale:read')")
    public ApiResponse<AfterSaleResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId
    ) {
        return ApiResponse.success(adminAfterSaleService.detail(principal, afterSaleId));
    }

    @GetMapping("/{afterSaleId}/evidence/{fileId}")
    @PreAuthorize("hasAuthority('aftersale:read')")
    public ResponseEntity<InputStreamResource> evidence(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId,
            @PathVariable Long fileId
    ) {
        return adminAfterSaleService.evidence(principal, afterSaleId, fileId);
    }

    @PostMapping("/{afterSaleId}/approve")
    @PreAuthorize("hasAuthority('aftersale:audit')")
    public ApiResponse<AfterSaleResponse> approve(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId,
            @RequestBody AdminAfterSaleAuditRequest request
    ) {
        return ApiResponse.success(adminAfterSaleService.approve(principal, afterSaleId, request));
    }

    @PostMapping("/{afterSaleId}/reject")
    @PreAuthorize("hasAuthority('aftersale:audit')")
    public ApiResponse<AfterSaleResponse> reject(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long afterSaleId,
            @RequestBody AdminAfterSaleAuditRequest request
    ) {
        return ApiResponse.success(adminAfterSaleService.reject(principal, afterSaleId, request));
    }
}
