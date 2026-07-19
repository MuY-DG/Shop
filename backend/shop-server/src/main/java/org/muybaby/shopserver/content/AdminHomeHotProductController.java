package org.muybaby.shopserver.content;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.AdminHomeAutoFillRequest;
import org.muybaby.shopserver.content.dto.AdminHomeAutoFillResponse;
import org.muybaby.shopserver.content.dto.AdminHomeProductRequest;
import org.muybaby.shopserver.content.dto.AdminHomeProductResponse;
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
@RequestMapping("/admin/home/hot-products")
public class AdminHomeHotProductController {

    private final HomeDecorationService service;

    public AdminHomeHotProductController(HomeDecorationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('content:home-hot:read', 'content:home-hot:write')")
    public ApiResponse<List<AdminHomeProductResponse>> list() {
        return ApiResponse.success(service.products(HomeProductSection.HOT));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('content:home-hot:write')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminHomeProductRequest request) {
        return ApiResponse.success(service.createProduct(HomeProductSection.HOT, request));
    }

    @PostMapping("/auto-fill")
    @PreAuthorize("hasAuthority('content:home-hot:write')")
    public ApiResponse<AdminHomeAutoFillResponse> autoFill(
            @Valid @RequestBody AdminHomeAutoFillRequest request
    ) {
        return ApiResponse.success(service.autoFillProducts(HomeProductSection.HOT, request.targetCount()));
    }

    @PutMapping("/{itemId}")
    @PreAuthorize("hasAuthority('content:home-hot:write')")
    public ApiResponse<Void> update(@PathVariable Long itemId, @Valid @RequestBody AdminHomeProductRequest request) {
        service.updateProduct(itemId, HomeProductSection.HOT, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/{itemId}")
    @PreAuthorize("hasAuthority('content:home-hot:write')")
    public ApiResponse<Void> delete(@PathVariable Long itemId) {
        service.deleteProduct(itemId, HomeProductSection.HOT);
        return ApiResponse.success();
    }
}
