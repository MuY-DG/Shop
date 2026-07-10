package org.muybaby.shopserver.user.address;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.user.address.dto.AddressResponse;
import org.muybaby.shopserver.user.address.dto.AddressUpsertRequest;
import org.muybaby.shopserver.user.address.service.AppAddressService;
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
@RequestMapping("/app/addresses")
public class AppAddressController {

    private final AppAddressService appAddressService;

    public AppAddressController(AppAddressService appAddressService) {
        this.appAddressService = appAddressService;
    }

    @GetMapping
    public ApiResponse<List<AddressResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(appAddressService.list(principal.subjectId()));
    }

    @GetMapping("/{addressId}")
    public ApiResponse<AddressResponse> get(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long addressId
    ) {
        return ApiResponse.success(appAddressService.get(principal.subjectId(), addressId));
    }

    @PostMapping
    public ApiResponse<AddressResponse> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AddressUpsertRequest request
    ) {
        return ApiResponse.success(appAddressService.create(principal.subjectId(), request));
    }

    @PutMapping("/{addressId}")
    public ApiResponse<AddressResponse> update(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long addressId,
            @Valid @RequestBody AddressUpsertRequest request
    ) {
        return ApiResponse.success(appAddressService.update(principal.subjectId(), addressId, request));
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long addressId
    ) {
        appAddressService.delete(principal.subjectId(), addressId);
        return ApiResponse.success();
    }

    @PostMapping("/{addressId}/default")
    public ApiResponse<AddressResponse> setDefault(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable long addressId
    ) {
        return ApiResponse.success(appAddressService.setDefault(principal.subjectId(), addressId));
    }
}
