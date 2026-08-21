package org.muybaby.shopserver.accountcancellation;

import org.muybaby.shopserver.accountcancellation.dto.AdminAccountCancellationQuery;
import org.muybaby.shopserver.accountcancellation.dto.AdminAccountCancellationResponse;
import org.muybaby.shopserver.accountcancellation.service.AdminAccountCancellationService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/compliance/account-cancellations")
public class AdminAccountCancellationController {

    private final AdminAccountCancellationService adminAccountCancellationService;

    public AdminAccountCancellationController(
            AdminAccountCancellationService adminAccountCancellationService
    ) {
        this.adminAccountCancellationService = adminAccountCancellationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('compliance:cancellation:read')")
    public ApiResponse<PageResult<AdminAccountCancellationResponse>> page(
            AdminAccountCancellationQuery query
    ) {
        return ApiResponse.success(adminAccountCancellationService.page(query));
    }
}
