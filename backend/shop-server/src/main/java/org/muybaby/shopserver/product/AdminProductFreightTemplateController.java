package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.product.dto.AdminFreightTemplateRequest;
import org.muybaby.shopserver.product.dto.AdminFreightTemplateResponse;
import org.muybaby.shopserver.product.service.ProductFreightTemplateReadMapper;
import org.muybaby.shopserver.product.service.ProductFreightTemplateService;
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
@RequestMapping("/admin/product/freight-templates")
public class AdminProductFreightTemplateController {

    private static final String READ_AUTHORITIES = "hasAnyAuthority(" +
            "'product:spu:create'," +
            "'product:spu:update'," +
            "'product:freight:create'," +
            "'product:freight:update')";

    private final ProductFreightTemplateService freightTemplateService;
    private final ProductFreightTemplateReadMapper readMapper;

    public AdminProductFreightTemplateController(
            ProductFreightTemplateService freightTemplateService,
            ProductFreightTemplateReadMapper readMapper
    ) {
        this.freightTemplateService = freightTemplateService;
        this.readMapper = readMapper;
    }

    @GetMapping
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<List<AdminFreightTemplateResponse>> list() {
        return ApiResponse.success(readMapper.list());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:freight:create')")
    public ApiResponse<Long> create(@Valid @RequestBody AdminFreightTemplateRequest request) {
        return ApiResponse.success(freightTemplateService.create(request));
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("hasAuthority('product:freight:update')")
    public ApiResponse<Void> update(
            @PathVariable Long templateId,
            @Valid @RequestBody AdminFreightTemplateRequest request
    ) {
        freightTemplateService.update(templateId, request);
        return ApiResponse.success();
    }
}
