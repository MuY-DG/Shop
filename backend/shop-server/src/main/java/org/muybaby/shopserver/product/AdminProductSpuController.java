package org.muybaby.shopserver.product;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminProductPurgeRequest;
import org.muybaby.shopserver.product.dto.AdminSpuDetailResponse;
import org.muybaby.shopserver.product.dto.AdminSpuListItemResponse;
import org.muybaby.shopserver.product.dto.AdminSpuQueryRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/product/spus")
public class AdminProductSpuController {

    private static final String PRODUCT_READ_AUTHORITY_ARGUMENTS =
            "'product:spu:create', " +
            "'product:spu:update', " +
            "'product:spu:publish', " +
            "'product:spu:delete', " +
            "'product:spu:restore', " +
            "'product:spu:purge', " +
            "'product:sku:stock', " +
            "'product:freight:create', " +
            "'product:freight:update', " +
            "'product:coupon:bind', " +
            "'product:coupon:create'";
    private static final String READ_AUTHORITIES =
            "hasAnyAuthority(" + PRODUCT_READ_AUTHORITY_ARGUMENTS + ")";
    private static final String PAGE_READ_AUTHORITIES =
            "hasAnyAuthority(" + PRODUCT_READ_AUTHORITY_ARGUMENTS + ", " +
            "'coupon:template:create', " +
            "'coupon:template:update')";

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
    @PreAuthorize(PAGE_READ_AUTHORITIES)
    public ApiResponse<PageResult<AdminSpuListItemResponse>> page(
            AdminSpuQueryRequest query,
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        if (query != null && Boolean.TRUE.equals(query.recycled())
                && !hasAnyPermission(
                principal,
                "product:spu:delete",
                "product:spu:restore",
                "product:spu:purge"
        )) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }
        return ApiResponse.success(productReadMapper.adminSpuPage(query));
    }

    @GetMapping("/{spuId}")
    @PreAuthorize(READ_AUTHORITIES)
    public ApiResponse<AdminSpuDetailResponse> detail(@PathVariable Long spuId) {
        return ApiResponse.success(productReadMapper.adminSpuDetail(spuId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:spu:create')")
    public ApiResponse<Long> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminSpuUpsertRequest request
    ) {
        return ApiResponse.success(adminProductService.createSpu(
                request,
                principal.permissions().contains("product:sku:stock")
        ));
    }

    @PutMapping("/{spuId}")
    @PreAuthorize("hasAuthority('product:spu:update')")
    public ApiResponse<Void> update(
            @PathVariable Long spuId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AdminSpuUpsertRequest request
    ) {
        adminProductService.updateSpu(
                spuId,
                request,
                principal.subjectId(),
                principal.permissions().contains("product:sku:stock")
        );
        return ApiResponse.success();
    }

    @PostMapping("/{spuId}/publish")
    @PreAuthorize("hasAuthority('product:spu:publish')")
    public ApiResponse<Void> publish(@PathVariable Long spuId) {
        adminProductService.publishSpu(spuId);
        return ApiResponse.success();
    }

    @PostMapping("/{spuId}/unpublish")
    @PreAuthorize("hasAuthority('product:spu:publish')")
    public ApiResponse<Void> unpublish(@PathVariable Long spuId) {
        adminProductService.unpublishSpu(spuId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{spuId}")
    @PreAuthorize("hasAuthority('product:spu:delete')")
    public ApiResponse<Void> delete(@PathVariable Long spuId) {
        adminProductService.deleteSpu(spuId);
        return ApiResponse.success();
    }

    @PostMapping("/{spuId}/restore")
    @PreAuthorize("hasAuthority('product:spu:restore')")
    public ApiResponse<Void> restore(@PathVariable Long spuId) {
        adminProductService.restoreSpu(spuId);
        return ApiResponse.success();
    }

    @PostMapping("/{spuId}/purge")
    @PreAuthorize("hasAuthority('product:spu:purge')")
    public ApiResponse<Void> purge(
            @PathVariable Long spuId,
            @Valid @RequestBody AdminProductPurgeRequest request
    ) {
        adminProductService.purgeSpu(spuId, request.confirmationTitle());
        return ApiResponse.success();
    }

    private boolean hasAnyPermission(AuthenticatedPrincipal principal, String... permissions) {
        if (principal == null) {
            return false;
        }
        for (String permission : permissions) {
            if (principal.permissions().contains(permission)) {
                return true;
            }
        }
        return false;
    }
}
