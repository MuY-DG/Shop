package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageFileMoveRequest;
import org.muybaby.shopserver.storage.dto.StorageFileQueryRequest;
import org.muybaby.shopserver.storage.dto.StorageFileResponse;
import org.muybaby.shopserver.storage.dto.StorageFileUsageResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/admin/files")
public class AdminFileController {

    private final StorageService storageService;

    public AdminFileController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('file:upload')")
    public ApiResponse<StorageFileResponse> upload(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam String purpose,
            @RequestParam(required = false) Long assetCategoryId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(storageService.uploadAdmin(principal, purpose, assetCategoryId, file));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('file:read')")
    public ApiResponse<PageResult<StorageFileResponse>> page(StorageFileQueryRequest query) {
        return ApiResponse.success(storageService.page(query));
    }

    @GetMapping("/{fileId}")
    @PreAuthorize("hasAuthority('file:read')")
    public ApiResponse<StorageFileResponse> detail(@PathVariable Long fileId) {
        return ApiResponse.success(storageService.detail(fileId));
    }

    @GetMapping("/{fileId}/usages")
    @PreAuthorize("hasAuthority('file:read')")
    public ApiResponse<List<StorageFileUsageResponse>> usages(@PathVariable Long fileId) {
        return ApiResponse.success(storageService.usages(fileId));
    }

    @PostMapping("/{fileId}/move")
    @PreAuthorize("hasAuthority('file:category')")
    public ApiResponse<Void> move(@PathVariable Long fileId, @RequestBody StorageFileMoveRequest request) {
        storageService.move(fileId, request.assetCategoryId());
        return ApiResponse.success();
    }

    @DeleteMapping("/{fileId}")
    @PreAuthorize("hasAuthority('file:delete')")
    public ApiResponse<Void> delete(@PathVariable Long fileId) {
        storageService.delete(fileId);
        return ApiResponse.success();
    }
}
