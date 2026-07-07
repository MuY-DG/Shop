package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AdminSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpuListItemResponse;
import org.muybaby.shopserver.product.dto.AdminSpuQueryRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/product/spus")
public class AdminProductSpuController {

    private final AdminProductService adminProductService;
    private final ProductReadMapper productReadMapper;

    public AdminProductSpuController(
            AdminProductService adminProductService,
            ProductReadMapper productReadMapper
    ) {
        this.adminProductService = adminProductService;
        this.productReadMapper = productReadMapper;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminSpuListItemResponse>> page(AdminSpuQueryRequest query) {
        return ApiResponse.success(productReadMapper.adminSpuPage(query));
    }

    @GetMapping("/{spuId}")
    public ApiResponse<AdminSpuDetailResponse> detail(@PathVariable Long spuId) {
        return ApiResponse.success(productReadMapper.adminSpuDetail(spuId));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AdminSpuUpsertRequest request) {
        return ApiResponse.success(adminProductService.createSpu(request));
    }

    @PutMapping("/{spuId}")
    public ApiResponse<Void> update(@PathVariable Long spuId, @Valid @RequestBody AdminSpuUpsertRequest request) {
        adminProductService.updateSpu(spuId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{spuId}/publish")
    public ApiResponse<Void> publish(@PathVariable Long spuId) {
        adminProductService.publishSpu(spuId);
        return ApiResponse.success();
    }

    @PostMapping("/{spuId}/unpublish")
    public ApiResponse<Void> unpublish(@PathVariable Long spuId) {
        adminProductService.unpublishSpu(spuId);
        return ApiResponse.success();
    }
}
