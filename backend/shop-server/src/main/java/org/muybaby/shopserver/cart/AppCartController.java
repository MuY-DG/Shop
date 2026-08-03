package org.muybaby.shopserver.cart;

import jakarta.validation.Valid;
import org.muybaby.shopserver.cart.dto.AddCartItemRequest;
import org.muybaby.shopserver.cart.dto.CartItemResponse;
import org.muybaby.shopserver.cart.dto.CartListResponse;
import org.muybaby.shopserver.cart.dto.DeleteCartItemsRequest;
import org.muybaby.shopserver.cart.dto.UpdateCartQuantityRequest;
import org.muybaby.shopserver.cart.service.AppCartService;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/cart")
public class AppCartController {

    private final AppCartService appCartService;

    public AppCartController(AppCartService appCartService) {
        this.appCartService = appCartService;
    }

    @GetMapping("/items")
    public ApiResponse<CartListResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ApiResponse.success(appCartService.list(principal));
    }

    @PostMapping("/items")
    public ApiResponse<CartItemResponse> add(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        return ApiResponse.success(appCartService.add(principal, request));
    }

    @PutMapping("/items/{cartItemId}/quantity")
    public ApiResponse<CartItemResponse> updateQuantity(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long cartItemId,
            @Valid @RequestBody UpdateCartQuantityRequest request
    ) {
        return ApiResponse.success(appCartService.updateQuantity(principal, cartItemId, request));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long cartItemId
    ) {
        appCartService.delete(principal, cartItemId);
        return ApiResponse.success();
    }

    @DeleteMapping("/items/batch")
    public ApiResponse<Void> deleteBatch(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody DeleteCartItemsRequest request
    ) {
        appCartService.deleteBatch(principal, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/items")
    public ApiResponse<Void> clear(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        appCartService.clear(principal);
        return ApiResponse.success();
    }
}
