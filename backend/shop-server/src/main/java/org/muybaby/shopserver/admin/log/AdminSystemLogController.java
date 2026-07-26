package org.muybaby.shopserver.admin.log;

import org.muybaby.shopserver.admin.log.dto.AdminSystemLogQuery;
import org.muybaby.shopserver.admin.log.dto.AdminSystemLogResponse;
import org.muybaby.shopserver.admin.log.service.AdminSystemLogQueryService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/system/logs")
public class AdminSystemLogController {

    private final AdminSystemLogQueryService queryService;

    public AdminSystemLogController(AdminSystemLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:log:read')")
    public ApiResponse<PageResult<AdminSystemLogResponse>> page(AdminSystemLogQuery query) {
        return ApiResponse.success(queryService.page(query));
    }
}
