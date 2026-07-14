package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/storage/config")
public class AdminStorageConfigController {

    private final StorageRuntimeConfigService storageRuntimeConfigService;

    public AdminStorageConfigController(StorageRuntimeConfigService storageRuntimeConfigService) {
        this.storageRuntimeConfigService = storageRuntimeConfigService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('storage:config:read')")
    public ApiResponse<AdminStorageConfigResponse> current() {
        return ApiResponse.success(storageRuntimeConfigService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('storage:config:write')")
    public ApiResponse<AdminStorageConfigResponse> update(@RequestBody AdminStorageConfigRequest request) {
        return ApiResponse.success(storageRuntimeConfigService.update(request));
    }
}
