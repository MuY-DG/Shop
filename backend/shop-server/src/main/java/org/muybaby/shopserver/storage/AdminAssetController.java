package org.muybaby.shopserver.storage;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageAssetBatchDeleteRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetBatchDeleteResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetBatchMoveRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetDisplayNameRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetMoveRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetQueryRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.service.StorageService;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/assets")
public class AdminAssetController {

    private final StorageService storageService;

    public AdminAssetController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('asset:upload')")
    public ApiResponse<StorageAssetResponse> upload(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) Long folderId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(storageService.uploadLibrary(principal, folderId, file));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('asset:read')")
    public ApiResponse<PageResult<StorageAssetResponse>> page(StorageAssetQueryRequest query) {
        return ApiResponse.success(storageService.page(query));
    }

    @GetMapping("/{assetId}")
    @PreAuthorize("hasAuthority('asset:read')")
    public ApiResponse<StorageAssetResponse> detail(@PathVariable Long assetId) {
        return ApiResponse.success(storageService.detail(assetId));
    }

    @PostMapping("/{assetId}/move")
    @PreAuthorize("hasAuthority('asset:folder')")
    public ApiResponse<Void> move(
            @PathVariable Long assetId,
            @Valid @RequestBody StorageAssetMoveRequest request
    ) {
        storageService.move(assetId, request.folderId());
        return ApiResponse.success();
    }

    @PostMapping("/batch-move")
    @PreAuthorize("hasAuthority('asset:folder')")
    public ApiResponse<Void> batchMove(@Valid @RequestBody StorageAssetBatchMoveRequest request) {
        storageService.moveBatch(request.assetIds(), request.folderId());
        return ApiResponse.success();
    }

    @PostMapping("/batch-delete")
    @PreAuthorize("hasAuthority('asset:delete')")
    public ApiResponse<StorageAssetBatchDeleteResponse> batchDelete(
            @Valid @RequestBody StorageAssetBatchDeleteRequest request
    ) {
        return ApiResponse.success(storageService.deleteBatch(request.assetIds()));
    }

    @PutMapping("/{assetId}/display-name")
    @PreAuthorize("hasAuthority('asset:folder')")
    public ApiResponse<StorageAssetResponse> updateDisplayName(
            @PathVariable Long assetId,
            @Valid @RequestBody StorageAssetDisplayNameRequest request
    ) {
        return ApiResponse.success(storageService.updateDisplayName(assetId, request.displayName()));
    }

    @DeleteMapping("/{assetId}")
    @PreAuthorize("hasAuthority('asset:delete')")
    public ApiResponse<Void> delete(@PathVariable Long assetId) {
        storageService.delete(assetId);
        return ApiResponse.success();
    }
}
