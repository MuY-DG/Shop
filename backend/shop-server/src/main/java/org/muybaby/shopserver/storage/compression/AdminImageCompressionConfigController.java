package org.muybaby.shopserver.storage.compression;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionRuntimeConfigService;
import org.muybaby.shopserver.storage.compression.dto.AdminImageCompressionConfigRequest;
import org.muybaby.shopserver.storage.compression.dto.AdminImageCompressionConfigResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/image-compression/config")
public class AdminImageCompressionConfigController {

    private final ImageCompressionRuntimeConfigService configService;

    public AdminImageCompressionConfigController(ImageCompressionRuntimeConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('image-compression:config:read')")
    public ApiResponse<AdminImageCompressionConfigResponse> current() {
        return ApiResponse.success(configService.current());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('image-compression:config:write')")
    public ApiResponse<AdminImageCompressionConfigResponse> update(
            @RequestBody AdminImageCompressionConfigRequest request
    ) {
        return ApiResponse.success(configService.update(request));
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('image-compression:config:write')")
    public ApiResponse<AdminImageCompressionConfigResponse> refresh() {
        return ApiResponse.success(configService.refreshUsage());
    }
}
