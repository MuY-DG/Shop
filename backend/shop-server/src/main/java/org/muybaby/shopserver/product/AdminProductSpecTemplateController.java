package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateRequest;
import org.muybaby.shopserver.product.dto.AdminSpecTemplateSummaryResponse;
import org.muybaby.shopserver.product.service.ProductSpecTemplateReadMapper;
import org.muybaby.shopserver.product.service.ProductSpecTemplateService;
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
@RequestMapping("/admin/product/spec-templates")
public class AdminProductSpecTemplateController {

    private static final String READ_AUTHORITIES = "hasAnyAuthority(" +
            "'product:spu:create'," +
            "'product:spu:update'," +
            "'product:spec-template:create'," +
            "'product:spec-template:update')";

    private final ProductSpecTemplateService templateService;
    private final ProductSpecTemplateReadMapper readMapper;

    public AdminProductSpecTemplateController(
            ProductSpecTemplateService templateService,
            ProductSpecTemplateReadMapper readMapper
    ) {
        this.templateService = templateService;
        this.readMapper = readMapper;
    }

    @GetMapping
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<List<AdminSpecTemplateSummaryResponse>> list() {
        return ApiResponse.success(readMapper.list());
    }

    @GetMapping("/{templateId}")
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<AdminSpecTemplateDetailResponse> detail(@PathVariable Long templateId) {
        return ApiResponse.success(readMapper.findDetail(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:spec-template:create')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminSpecTemplateRequest request) {
        return ApiResponse.success(templateService.create(request));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("hasAuthority('product:spec-template:update')")
    public ApiResponse<Void> update(
            @PathVariable Long templateId,
            @Valid @RequestBody AdminSpecTemplateRequest request
    ) {
        templateService.update(templateId, request);
        return ApiResponse.success();
    }
}
