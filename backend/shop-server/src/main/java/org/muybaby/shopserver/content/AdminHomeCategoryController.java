package org.muybaby.shopserver.content;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.AdminHomeCategoryRequest;
import org.muybaby.shopserver.content.dto.AdminHomeCategoryResponse;
import org.muybaby.shopserver.content.service.HomeDecorationService;
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
@RequestMapping("/admin/home/categories")
public class AdminHomeCategoryController {

    private final HomeDecorationService service;

    public AdminHomeCategoryController(HomeDecorationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('content:home-category:read', 'content:home-category:write')")
    public ApiResponse<List<AdminHomeCategoryResponse>> list() {
        return ApiResponse.success(service.categories());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('content:home-category:write')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminHomeCategoryRequest request) {
        return ApiResponse.success(service.createCategory(request));
    }

    @PutMapping("/{itemId}")
    @PreAuthorize("hasAuthority('content:home-category:write')")
    public ApiResponse<Void> update(
            @PathVariable Long itemId,
            @Valid @RequestBody AdminHomeCategoryRequest request
    ) {
        service.updateCategory(itemId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{itemId}")
    @PreAuthorize("hasAuthority('content:home-category:write')")
    public ApiResponse<Void> delete(@PathVariable Long itemId) {
        service.deleteCategory(itemId);
        return ApiResponse.success();
    }
}
