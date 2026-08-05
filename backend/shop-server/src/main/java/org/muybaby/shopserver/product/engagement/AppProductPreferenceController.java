package org.muybaby.shopserver.product.engagement;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.product.engagement.dto.DeleteBrowseHistoryItemsRequest;
import org.muybaby.shopserver.product.engagement.dto.DeleteFavoriteItemsRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductBrowseHistoryPageResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductBrowseRecordResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductEngagementPageRequest;
import org.muybaby.shopserver.product.engagement.dto.ProductFavoriteItemResponse;
import org.muybaby.shopserver.product.engagement.dto.ProductFavoriteStatusResponse;
import org.muybaby.shopserver.product.engagement.service.AppProductPreferenceService;
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
@RequestMapping("/app/users/me")
public class AppProductPreferenceController {

    private final AppProductPreferenceService preferenceService;

    public AppProductPreferenceController(AppProductPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping("/favorites")
    public ApiResponse<PageResult<ProductFavoriteItemResponse>> favorites(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            ProductEngagementPageRequest request
    ) {
        return ApiResponse.success(preferenceService.favorites(principal, request));
    }

    @GetMapping("/favorites/{spuId}")
    public ApiResponse<ProductFavoriteStatusResponse> favoriteStatus(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        return ApiResponse.success(preferenceService.favoriteStatus(principal, spuId));
    }

    @PutMapping("/favorites/{spuId}")
    public ApiResponse<ProductFavoriteStatusResponse> addFavorite(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        return ApiResponse.success(preferenceService.addFavorite(principal, spuId));
    }

    @DeleteMapping("/favorites/{spuId}")
    public ApiResponse<Void> removeFavorite(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        preferenceService.removeFavorite(principal, spuId);
        return ApiResponse.success();
    }

    @DeleteMapping("/favorites/batch")
    public ApiResponse<Void> removeFavorites(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody DeleteFavoriteItemsRequest request
    ) {
        preferenceService.removeFavorites(principal, request);
        return ApiResponse.success();
    }

    @PostMapping("/browse-history/{spuId}")
    public ApiResponse<ProductBrowseRecordResponse> recordBrowse(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        return ApiResponse.success(preferenceService.recordBrowse(principal, spuId));
    }

    @GetMapping("/browse-history")
    public ApiResponse<ProductBrowseHistoryPageResponse> browseHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            ProductEngagementPageRequest request
    ) {
        return ApiResponse.success(preferenceService.browseHistory(principal, request));
    }

    @DeleteMapping("/browse-history/{spuId}")
    public ApiResponse<Void> deleteBrowse(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long spuId
    ) {
        preferenceService.deleteBrowse(principal, spuId);
        return ApiResponse.success();
    }

    @DeleteMapping("/browse-history/batch")
    public ApiResponse<Void> deleteBrowseBatch(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody DeleteBrowseHistoryItemsRequest request
    ) {
        preferenceService.deleteBrowseBatch(principal, request);
        return ApiResponse.success();
    }

    @DeleteMapping("/browse-history")
    public ApiResponse<Void> clearBrowseHistory(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        preferenceService.clearBrowseHistory(principal);
        return ApiResponse.success();
    }
}
