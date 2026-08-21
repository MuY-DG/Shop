package org.muybaby.shopserver.accountcancellation;

import jakarta.validation.Valid;
import org.muybaby.shopserver.accountcancellation.dto.AccountCancellationEligibilityResponse;
import org.muybaby.shopserver.accountcancellation.dto.AccountCancellationRequest;
import org.muybaby.shopserver.accountcancellation.dto.AccountCancellationResponse;
import org.muybaby.shopserver.accountcancellation.service.AccountCancellationService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/account-cancellation")
public class AppAccountCancellationController {

    private final AccountCancellationService accountCancellationService;

    public AppAccountCancellationController(AccountCancellationService accountCancellationService) {
        this.accountCancellationService = accountCancellationService;
    }

    @GetMapping("/eligibility")
    public ApiResponse<AccountCancellationEligibilityResponse> eligibility(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(accountCancellationService.eligibility(principal));
    }

    @PostMapping
    public ApiResponse<AccountCancellationResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AccountCancellationRequest request
    ) {
        return ApiResponse.success(accountCancellationService.cancel(principal, request));
    }
}
