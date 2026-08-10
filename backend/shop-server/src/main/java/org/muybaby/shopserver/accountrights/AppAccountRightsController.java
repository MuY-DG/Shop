package org.muybaby.shopserver.accountrights;

import jakarta.validation.Valid;
import org.muybaby.shopserver.accountrights.dto.AccountRightsRequestDetailResponse;
import org.muybaby.shopserver.accountrights.dto.AccountRightsRequestResponse;
import org.muybaby.shopserver.accountrights.dto.AccountRightsVersionRequest;
import org.muybaby.shopserver.accountrights.dto.AppAccountRightsSubmitRequest;
import org.muybaby.shopserver.accountrights.service.AccountRightsService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/account-rights/requests")
public class AppAccountRightsController {

    private final AccountRightsService accountRightsService;

    public AppAccountRightsController(AccountRightsService accountRightsService) {
        this.accountRightsService = accountRightsService;
    }

    @PostMapping
    public ApiResponse<AccountRightsRequestResponse> submit(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AppAccountRightsSubmitRequest request
    ) {
        return ApiResponse.success(accountRightsService.submit(principal, request));
    }

    @GetMapping
    public ApiResponse<List<AccountRightsRequestResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(accountRightsService.listForUser(principal));
    }

    @GetMapping("/{requestId}")
    public ApiResponse<AccountRightsRequestDetailResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId
    ) {
        return ApiResponse.success(accountRightsService.detailForUser(principal, requestId));
    }

    @PostMapping("/{requestId}/withdraw")
    public ApiResponse<AccountRightsRequestResponse> withdraw(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId,
            @Valid @RequestBody AccountRightsVersionRequest request
    ) {
        return ApiResponse.success(accountRightsService.withdraw(principal, requestId, request));
    }
}
