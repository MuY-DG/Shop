package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminProductParameterDefinitionRequest;
import org.muybaby.shopserver.product.dto.AdminProductParameterDefinitionResponse;
import org.muybaby.shopserver.product.dto.AdminSpuParameterValueResponse;
import org.muybaby.shopserver.product.dto.AdminSpuParameterValuesRequest;
import org.muybaby.shopserver.product.service.ProductParameterService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/product")
public class AdminProductParameterController {

    private static final String PRODUCT_PARAMETER_READ = "hasAnyAuthority(" +
            "'product:parameter:read', 'product:parameter:write', " +
            "'product:spu:create', 'product:spu:update')";

    private final ProductParameterService service;

    public AdminProductParameterController(ProductParameterService service) {
        this.service = service;
    }

    @GetMapping("/parameter-definitions")
    @PreAuthorize(PRODUCT_PARAMETER_READ)
    public ApiResponse<List<AdminProductParameterDefinitionResponse>> definitions(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "false") boolean enabledOnly
    ) {
        return ApiResponse.success(service.definitions(categoryId, enabledOnly));
    }

    @GetMapping("/parameter-definitions/{parameterId}")
    @PreAuthorize(PRODUCT_PARAMETER_READ)
    public ApiResponse<AdminProductParameterDefinitionResponse> definition(@PathVariable Long parameterId) {
        return ApiResponse.success(service.definition(parameterId));
    }

    @PostMapping("/parameter-definitions")
    @PreAuthorize("hasAuthority('product:parameter:write')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminProductParameterDefinitionRequest request) {
        return ApiResponse.success(service.createDefinition(request));
    }

    @PutMapping("/parameter-definitions/{parameterId}")
    @PreAuthorize("hasAuthority('product:parameter:write')")
    public ApiResponse<Void> update(
            @PathVariable Long parameterId,
            @Valid @RequestBody AdminProductParameterDefinitionRequest request
    ) {
        service.updateDefinition(parameterId, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/parameter-definitions/{parameterId}")
    @PreAuthorize("hasAuthority('product:parameter:write')")
    public ApiResponse<Void> delete(@PathVariable Long parameterId) {
        service.deleteDefinition(parameterId);
        return ApiResponse.success();
    }

    @GetMapping("/spus/{spuId}/parameters")
    @PreAuthorize(PRODUCT_PARAMETER_READ)
    public ApiResponse<List<AdminSpuParameterValueResponse>> values(@PathVariable Long spuId) {
        return ApiResponse.success(service.spuValues(spuId));
    }

    @PutMapping("/spus/{spuId}/parameters")
    @PreAuthorize("hasAnyAuthority('product:spu:create', 'product:spu:update')")
    public ApiResponse<Void> replaceValues(
            @PathVariable Long spuId,
            @Valid @RequestBody AdminSpuParameterValuesRequest request
    ) {
        service.replaceSpuValues(spuId, request.values());
        return ApiResponse.success();
    }
}
