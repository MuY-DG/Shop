package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminCategoryResponse;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.web.bind.annotation.GetMapping;
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
    public ApiResponse<List<AdminCategoryResponse>> list() {
        return ApiResponse.success(productReadMapper.adminCategoryTree());
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AdminCategoryRequest request) {
        return ApiResponse.success(adminProductService.createCategory(request));
    }

    @PutMapping("/{categoryId}")
    public ApiResponse<Void> update(@PathVariable Long categoryId, @Valid @RequestBody AdminCategoryRequest request) {
        adminProductService.updateCategory(categoryId, request);
        return ApiResponse.success();
    }
}
