package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigResponse;
import org.muybaby.shopserver.storage.dto.AdminStorageBucketListRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageBucketResponse;
import org.muybaby.shopserver.storage.dto.AdminStorageDomainListRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageDomainResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @PostMapping("/buckets")
    @PreAuthorize("hasAuthority('storage:config:write')")
    public ApiResponse<List<AdminStorageBucketResponse>> listBuckets(
            @RequestBody AdminStorageBucketListRequest request
    ) {
        return ApiResponse.success(storageRuntimeConfigService.listBuckets(request));
    }

    @PostMapping("/domains")
    @PreAuthorize("hasAuthority('storage:config:write')")
    public ApiResponse<List<AdminStorageDomainResponse>> listDomains(
            @RequestBody AdminStorageDomainListRequest request
    ) {
        return ApiResponse.success(storageRuntimeConfigService.listDomains(request));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('storage:config:write')")
    public ApiResponse<AdminStorageConfigResponse> update(@RequestBody AdminStorageConfigRequest request) {
        return ApiResponse.success(storageRuntimeConfigService.update(request));
    }
}
