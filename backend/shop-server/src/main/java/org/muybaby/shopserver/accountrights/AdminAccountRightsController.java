package org.muybaby.shopserver.accountrights;

import jakarta.validation.Valid;
import org.muybaby.shopserver.accountrights.dto.AccountRightsRequestDetailResponse;
import org.muybaby.shopserver.accountrights.dto.AccountRightsRequestResponse;
import org.muybaby.shopserver.accountrights.dto.AdminAccountRightsQuery;
import org.muybaby.shopserver.accountrights.dto.AdminAccountRightsTransitionRequest;
import org.muybaby.shopserver.accountrights.service.AccountRightsService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/account-rights/requests")
public class AdminAccountRightsController {

    private final AccountRightsService accountRightsService;

    public AdminAccountRightsController(AccountRightsService accountRightsService) {
        this.accountRightsService = accountRightsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('account-rights:read')")
    public ApiResponse<PageResult<AccountRightsRequestResponse>> page(AdminAccountRightsQuery query) {
        return ApiResponse.success(accountRightsService.pageForAdmin(query));
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAuthority('account-rights:read')")
    public ApiResponse<AccountRightsRequestDetailResponse> detail(@PathVariable Long requestId) {
        return ApiResponse.success(accountRightsService.detailForAdmin(requestId));
    }

    @PostMapping("/{requestId}/review")
    @PreAuthorize("hasAuthority('account-rights:manage')")
    public ApiResponse<AccountRightsRequestResponse> review(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId,
            @Valid @RequestBody AdminAccountRightsTransitionRequest request
    ) {
        return ApiResponse.success(accountRightsService.adminTransition(
                principal, requestId, AccountRightsAdminAction.REVIEW, request));
    }

    @PostMapping("/{requestId}/approve")
    @PreAuthorize("hasAuthority('account-rights:manage')")
    public ApiResponse<AccountRightsRequestResponse> approve(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId,
            @Valid @RequestBody AdminAccountRightsTransitionRequest request
    ) {
        return ApiResponse.success(accountRightsService.adminTransition(
                principal, requestId, AccountRightsAdminAction.APPROVE, request));
    }

    @PostMapping("/{requestId}/reject")
    @PreAuthorize("hasAuthority('account-rights:manage')")
    public ApiResponse<AccountRightsRequestResponse> reject(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId,
            @Valid @RequestBody AdminAccountRightsTransitionRequest request
    ) {
        return ApiResponse.success(accountRightsService.adminTransition(
                principal, requestId, AccountRightsAdminAction.REJECT, request));
    }

    @PostMapping("/{requestId}/complete")
    @PreAuthorize("hasAuthority('account-rights:manage')")
    public ApiResponse<AccountRightsRequestResponse> complete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId,
            @Valid @RequestBody AdminAccountRightsTransitionRequest request
    ) {
        return ApiResponse.success(accountRightsService.adminTransition(
                principal, requestId, AccountRightsAdminAction.COMPLETE, request));
    }
}
