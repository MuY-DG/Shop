package org.muybaby.shopserver.storage;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetCategoryRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetCategoryResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/file-categories")
public class AdminFileCategoryController {

    private final StorageService storageService;

    public AdminFileCategoryController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('file:read', 'file:category')")
    public ApiResponse<List<StorageAssetCategoryResponse>> list() {
        return ApiResponse.success(storageService.categoryTree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('file:category')")
    public ApiResponse<Long> create(@Valid @RequestBody StorageAssetCategoryRequest request) {
        return ApiResponse.success(storageService.createCategory(request));
    }

    @PutMapping("/{categoryId}")
    @PreAuthorize("hasAuthority('file:category')")
    public ApiResponse<Void> update(@PathVariable Long categoryId, @Valid @RequestBody StorageAssetCategoryRequest request) {
        storageService.updateCategory(categoryId, request);
        return ApiResponse.success();
    }
}
