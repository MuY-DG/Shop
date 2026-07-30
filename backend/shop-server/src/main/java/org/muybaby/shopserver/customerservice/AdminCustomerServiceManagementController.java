package org.muybaby.shopserver.customerservice;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserUpdateRequest;
import org.muybaby.shopserver.customerservice.service.CustomerServiceManagementService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/customer-service/management")
public class AdminCustomerServiceManagementController {

    private final CustomerServiceManagementService managementService;

    public AdminCustomerServiceManagementController(CustomerServiceManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('customer-service:management:read')")
    public ApiResponse<List<ManagedUserResponse>> users(
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(managementService.users(keyword));
    }

    @PutMapping("/users/{adminUserId}")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<ManagedUserResponse> updateUser(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long adminUserId,
            @Valid @RequestBody ManagedUserUpdateRequest request
    ) {
        return ApiResponse.success(
                managementService.updateUser(principal.subjectId(), adminUserId, request)
        );
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('customer-service:management:read')")
    public ApiResponse<CustomerServiceConfigResponse> config() {
        return ApiResponse.success(managementService.config());
    }

    @PutMapping("/config")
    @PreAuthorize("""
            hasAuthority('customer-service:routing:update')
            and hasAuthority('customer-service:identity:update')
            """)
    public ApiResponse<CustomerServiceConfigResponse> updateConfig(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CustomerServiceConfigUpdateRequest request
    ) {
        return ApiResponse.success(
                managementService.updateConfig(principal.subjectId(), request)
        );
    }
}
