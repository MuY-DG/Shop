package org.muybaby.shopserver.admin.rbac;

import jakarta.validation.Valid;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleGrantRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleGrantResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleQueryRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminRoleUpsertRequest;
import org.muybaby.shopserver.admin.rbac.service.AdminManagementService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/system/roles")
public class AdminRoleController {

    private final AdminManagementService adminManagementService;

    public AdminRoleController(AdminManagementService adminManagementService) {
        this.adminManagementService = adminManagementService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('system:role:read', 'system:role:update')")
    public ApiResponse<PageResult<AdminRoleResponse>> page(AdminRoleQueryRequest query) {
        return ApiResponse.success(adminManagementService.rolePage(query));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:role:create')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminRoleUpsertRequest request) {
        return ApiResponse.success(adminManagementService.createRole(request));
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("hasAuthority('system:role:update')")
    public ApiResponse<Void> update(@PathVariable Long roleId, @Valid @RequestBody AdminRoleUpsertRequest request) {
        adminManagementService.updateRole(roleId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAnyAuthority('system:role:delete', 'system:role:update')")
    public ApiResponse<Void> delete(@PathVariable Long roleId) {
        adminManagementService.deleteRole(roleId);
        return ApiResponse.success();
    }

    @GetMapping("/{roleId}/grants")
    @PreAuthorize("hasAnyAuthority('system:role:read', 'system:role:update', 'system:role:assign')")
    public ApiResponse<AdminRoleGrantResponse> grants(@PathVariable Long roleId) {
        return ApiResponse.success(adminManagementService.roleGrants(roleId));
    }

    @PutMapping("/{roleId}/grants")
    @PreAuthorize("hasAuthority('system:role:assign')")
    public ApiResponse<Void> updateGrants(
            @PathVariable Long roleId,
            @Valid @RequestBody AdminRoleGrantRequest request
    ) {
        adminManagementService.updateRoleGrants(roleId, request);
        return ApiResponse.success();
    }
}
