package org.muybaby.shopserver.storage;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetFolderRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetFolderResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/admin/asset-folders")
public class AdminAssetFolderController {

    private final StorageService storageService;

    public AdminAssetFolderController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('asset:read', 'asset:folder')")
    public ApiResponse<List<StorageAssetFolderResponse>> list() {
        return ApiResponse.success(storageService.folderTree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('asset:folder')")
    public ApiResponse<StorageAssetFolderResponse> create(
            @Valid @RequestBody StorageAssetFolderRequest request
    ) {
        return ApiResponse.success(storageService.createFolder(request));
    }

    @PutMapping("/{folderId}")
    @PreAuthorize("hasAuthority('asset:folder')")
    public ApiResponse<StorageAssetFolderResponse> update(
            @PathVariable Long folderId,
            @Valid @RequestBody StorageAssetFolderRequest request
    ) {
        return ApiResponse.success(storageService.updateFolder(folderId, request));
    }

    @DeleteMapping("/{folderId}")
    @PreAuthorize("hasAuthority('asset:folder')")
    public ApiResponse<Void> delete(@PathVariable Long folderId) {
        storageService.deleteFolder(folderId);
        return ApiResponse.success();
    }
}
