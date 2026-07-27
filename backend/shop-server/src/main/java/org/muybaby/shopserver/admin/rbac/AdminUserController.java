package org.muybaby.shopserver.admin.rbac;

import jakarta.validation.Valid;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserCreateRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserQueryRequest;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserResponse;
import org.muybaby.shopserver.admin.rbac.dto.AdminUserUpdateRequest;
import org.muybaby.shopserver.admin.rbac.service.AdminManagementService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.auth.dto.AdminSessionResponse;
import org.muybaby.shopserver.auth.session.AdminSessionManagementService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/system/users")
public class AdminUserController {

    private final AdminManagementService adminManagementService;
    private final AdminSessionManagementService adminSessionManagementService;

    public AdminUserController(
            AdminManagementService adminManagementService,
            AdminSessionManagementService adminSessionManagementService
    ) {
        this.adminManagementService = adminManagementService;
        this.adminSessionManagementService = adminSessionManagementService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('system:user:read', 'system:user:update')")
    public ApiResponse<PageResult<AdminUserResponse>> page(AdminUserQueryRequest query) {
        return ApiResponse.success(adminManagementService.userPage(query));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:create')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminUserCreateRequest request) {
        return ApiResponse.success(adminManagementService.createUser(request));
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:user:update')")
    public ApiResponse<Void> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long userId,
            @Valid @RequestBody AdminUserUpdateRequest request
    ) {
        adminManagementService.updateUser(principal.subjectId(), userId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasAuthority('system:user:disable')")
    public ApiResponse<Void> disable(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long userId
    ) {
        adminManagementService.disableUser(principal.subjectId(), userId);
        return ApiResponse.success();
    }

    @GetMapping("/{userId}/sessions")
    @PreAuthorize("hasAnyAuthority('system:user:session:read', 'system:user:session:revoke')")
    public ApiResponse<List<AdminSessionResponse>> sessions(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long userId
    ) {
        return ApiResponse.success(adminSessionManagementService.sessions(userId, principal.sessionId()));
    }

    @DeleteMapping("/{userId}/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('system:user:session:revoke')")
    public ApiResponse<Void> revokeSession(
            @PathVariable Long userId,
            @PathVariable String sessionId
    ) {
        adminSessionManagementService.revokeSession(userId, sessionId);
        return ApiResponse.success();
    }

    @PostMapping("/{userId}/logout-all")
    @PreAuthorize("hasAuthority('system:user:session:revoke')")
    public ApiResponse<Void> logoutAll(@PathVariable Long userId) {
        adminSessionManagementService.revokeAllSessions(userId);
        return ApiResponse.success();
    }
}
