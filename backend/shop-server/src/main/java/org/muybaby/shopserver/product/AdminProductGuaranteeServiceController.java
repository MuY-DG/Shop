package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceQueryRequest;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceRequest;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceResponse;
import org.muybaby.shopserver.product.dto.AdminGuaranteeServiceVisibilityRequest;
import org.muybaby.shopserver.product.service.ProductGuaranteeServiceReadMapper;
import org.muybaby.shopserver.product.service.ProductGuaranteeServiceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/product/guarantee-services")
public class AdminProductGuaranteeServiceController {

    private static final String READ_AUTHORITIES = "hasAnyAuthority(" +
            "'product:spu:create'," +
            "'product:spu:update'," +
            "'product:guarantee:create'," +
            "'product:guarantee:update'," +
            "'product:guarantee:delete'," +
            "'product:guarantee:visibility')";

    private final ProductGuaranteeServiceService guaranteeService;
    private final ProductGuaranteeServiceReadMapper readMapper;

    public AdminProductGuaranteeServiceController(
            ProductGuaranteeServiceService guaranteeService,
            ProductGuaranteeServiceReadMapper readMapper
    ) {
        this.guaranteeService = guaranteeService;
        this.readMapper = readMapper;
    }

    @GetMapping
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<PageResult<AdminGuaranteeServiceResponse>> page(AdminGuaranteeServiceQueryRequest query) {
        return ApiResponse.success(readMapper.page(query));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:guarantee:create')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminGuaranteeServiceRequest request) {
        return ApiResponse.success(guaranteeService.create(request));
    }

    @PutMapping("/{serviceId}")
    @PreAuthorize("hasAuthority('product:guarantee:update')")
    public ApiResponse<Void> update(
            @PathVariable Long serviceId,
            @Valid @RequestBody AdminGuaranteeServiceRequest request
    ) {
        guaranteeService.update(serviceId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{serviceId}/visibility")
    @PreAuthorize("hasAuthority('product:guarantee:visibility')")
    public ApiResponse<Void> updateVisibility(
            @PathVariable Long serviceId,
            @Valid @RequestBody AdminGuaranteeServiceVisibilityRequest request
    ) {
        guaranteeService.updateVisibility(serviceId, request.visible());
        return ApiResponse.success();
    }

    @DeleteMapping("/{serviceId}")
    @PreAuthorize("hasAuthority('product:guarantee:delete')")
    public ApiResponse<Void> delete(@PathVariable Long serviceId) {
        guaranteeService.delete(serviceId);
        return ApiResponse.success();
    }
}
