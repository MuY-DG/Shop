package org.muybaby.shopserver.customerservice;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceIdentityUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.CustomerServiceRoutingUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.GuestUserResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserCreateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserManagerUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserNameUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceManagementDtos.ManagedUserResponse;
import org.muybaby.shopserver.customerservice.service.CustomerServiceManagementService;
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

import java.util.List;

@RestController
@RequestMapping("/admin/customer-service/management")
public class AdminCustomerServiceManagementController {

    private final CustomerServiceManagementService managementService;

    public AdminCustomerServiceManagementController(CustomerServiceManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<List<ManagedUserResponse>> users(
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(managementService.users(keyword));
    }

    @GetMapping("/guests")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<List<GuestUserResponse>> guests(
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(managementService.guests(keyword));
    }

    @PostMapping("/users/{adminUserId}")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<ManagedUserResponse> addUser(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long adminUserId,
            @Valid @RequestBody ManagedUserCreateRequest request
    ) {
        return ApiResponse.success(managementService.addUser(
                principal.subjectId(), adminUserId, request));
    }

    @PutMapping("/users/{adminUserId}/name")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<ManagedUserResponse> updateName(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long adminUserId,
            @Valid @RequestBody ManagedUserNameUpdateRequest request
    ) {
        return ApiResponse.success(managementService.updateName(
                principal.subjectId(), adminUserId, request));
    }

    @PutMapping("/users/{adminUserId}/manager")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<ManagedUserResponse> updateManager(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long adminUserId,
            @Valid @RequestBody ManagedUserManagerUpdateRequest request
    ) {
        return ApiResponse.success(managementService.updateManager(
                principal.subjectId(), adminUserId, request));
    }

    @DeleteMapping("/users/{adminUserId}")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<Void> deleteUser(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long adminUserId
    ) {
        managementService.deleteUser(principal.subjectId(), adminUserId);
        return ApiResponse.success();
    }

    @GetMapping("/config")
    @PreAuthorize("hasAuthority('customer-service:management:read')")
    public ApiResponse<CustomerServiceConfigResponse> config() {
        return ApiResponse.success(managementService.config());
    }

    @PutMapping("/routing")
    @PreAuthorize("hasAuthority('customer-service:routing:update')")
    public ApiResponse<CustomerServiceConfigResponse> updateRouting(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CustomerServiceRoutingUpdateRequest request
    ) {
        return ApiResponse.success(managementService.updateRouting(
                principal.subjectId(), request));
    }

    @PutMapping("/identity")
    @PreAuthorize("hasAuthority('customer-service:identity:update')")
    public ApiResponse<CustomerServiceConfigResponse> updateIdentity(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CustomerServiceIdentityUpdateRequest request
    ) {
        return ApiResponse.success(managementService.updateIdentity(
                principal.subjectId(), request));
    }
}
