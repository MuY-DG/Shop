package org.muybaby.shopserver.aftersale;

import org.muybaby.shopserver.aftersale.dto.MerchantReturnAddressRequest;
import org.muybaby.shopserver.aftersale.dto.MerchantReturnAddressResponse;
import org.muybaby.shopserver.aftersale.service.MerchantReturnAddressService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/admin/after-sale-return-addresses")
public class AdminReturnAddressController {

    private final MerchantReturnAddressService service;

    public AdminReturnAddressController(MerchantReturnAddressService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('aftersale:read')")
    public ApiResponse<List<MerchantReturnAddressResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(service.list(principal));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('aftersale:return-address:write')")
    public ApiResponse<MerchantReturnAddressResponse> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestBody MerchantReturnAddressRequest request
    ) {
        return ApiResponse.success(service.create(principal, request));
    }

    @PutMapping("/{addressId}")
    @PreAuthorize("hasAuthority('aftersale:return-address:write')")
    public ApiResponse<MerchantReturnAddressResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long addressId,
            @RequestBody MerchantReturnAddressRequest request
    ) {
        return ApiResponse.success(service.update(principal, addressId, request));
    }

    @DeleteMapping("/{addressId}")
    @PreAuthorize("hasAuthority('aftersale:return-address:write')")
    public ApiResponse<Void> disable(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long addressId
    ) {
        service.disable(principal, addressId);
        return ApiResponse.success();
    }
}
