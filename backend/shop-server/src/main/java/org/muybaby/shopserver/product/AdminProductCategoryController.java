package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminCategoryResponse;
import org.muybaby.shopserver.product.dto.AdminCategoryPositionRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/product/categories")
public class AdminProductCategoryController {

    private static final String READ_AUTHORITIES = "hasAnyAuthority(" +
            "'product:category:create', " +
            "'product:category:update', " +
            "'product:spu:create', " +
            "'product:spu:update', " +
            "'product:spu:publish', " +
            "'product:spu:delete', " +
            "'product:sku:stock', " +
            "'product:freight:create', " +
            "'product:freight:update', " +
            "'product:coupon:bind', " +
            "'product:coupon:create')";

    private final AdminProductService adminProductService;
    private final ProductReadMapper productReadMapper;

    public AdminProductCategoryController(
            AdminProductService adminProductService,
            ProductReadMapper productReadMapper
    ) {
        this.adminProductService = adminProductService;
        this.productReadMapper = productReadMapper;
    }

    @GetMapping
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<List<AdminCategoryResponse>> list() {
        return ApiResponse.success(productReadMapper.adminCategoryTree());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:category:create')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminCategoryRequest request) {
        return ApiResponse.success(adminProductService.createCategory(request));
    }

    @PutMapping("/{categoryId}")
    @PreAuthorize("hasAuthority('product:category:update')")
    public ApiResponse<Void> update(@PathVariable Long categoryId, @Valid @RequestBody AdminCategoryRequest request) {
        adminProductService.updateCategory(categoryId, request);
        return ApiResponse.success();
    }

    @PutMapping("/{categoryId}/position")
    @PreAuthorize("hasAuthority('product:category:update')")
    public ApiResponse<Void> updatePosition(
            @PathVariable Long categoryId,
            @Valid @RequestBody AdminCategoryPositionRequest request
    ) {
        adminProductService.updateCategoryPosition(categoryId, request);
        return ApiResponse.success();
    }
}
