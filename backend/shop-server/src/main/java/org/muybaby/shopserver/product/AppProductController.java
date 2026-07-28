package org.muybaby.shopserver.product;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AppCategoryResponse;
import org.muybaby.shopserver.product.dto.AppProductFilterGroupResponse;
import org.muybaby.shopserver.product.dto.AppSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AppSpuListItemResponse;
import org.muybaby.shopserver.product.dto.ProductPageRequest;
import org.muybaby.shopserver.product.service.AppProductService;
import org.muybaby.shopserver.product.service.ProductParameterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/product")
public class AppProductController {

    private final AppProductService appProductService;
    private final ProductParameterService productParameterService;

    public AppProductController(
            AppProductService appProductService,
            ProductParameterService productParameterService
    ) {
        this.appProductService = appProductService;
        this.productParameterService = productParameterService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<AppCategoryResponse>> categories() {
        return ApiResponse.success(appProductService.categories());
    }

    @GetMapping("/spus")
    public ApiResponse<PageResult<AppSpuListItemResponse>> page(ProductPageRequest request) {
        return ApiResponse.success(appProductService.page(request));
    }

    @GetMapping("/filter-facets")
    public ApiResponse<List<AppProductFilterGroupResponse>> filterFacets(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(productParameterService.filterFacets(categoryId, keyword));
    }

    @GetMapping("/spus/{spuId}")
    public ApiResponse<AppSpuDetailResponse> detail(@PathVariable Long spuId) {
        return ApiResponse.success(appProductService.detail(spuId));
    }
}
