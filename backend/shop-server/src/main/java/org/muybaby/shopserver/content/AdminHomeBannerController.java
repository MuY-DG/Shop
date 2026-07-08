package org.muybaby.shopserver.content;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.content.dto.AdminHomeBannerQueryRequest;
import org.muybaby.shopserver.content.dto.AdminHomeBannerRequest;
import org.muybaby.shopserver.content.dto.AdminHomeBannerResponse;
import org.muybaby.shopserver.content.service.HomeBannerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/home/banners")
public class AdminHomeBannerController {

    private final HomeBannerService homeBannerService;

    public AdminHomeBannerController(HomeBannerService homeBannerService) {
        this.homeBannerService = homeBannerService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminHomeBannerResponse>> page(AdminHomeBannerQueryRequest query) {
        return ApiResponse.success(homeBannerService.page(query));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AdminHomeBannerRequest request) {
        return ApiResponse.success(homeBannerService.create(request));
    }

    @PutMapping("/{bannerId}")
    public ApiResponse<Void> update(@PathVariable Long bannerId, @Valid @RequestBody AdminHomeBannerRequest request) {
        homeBannerService.update(bannerId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{bannerId}/enable")
    public ApiResponse<Void> enable(@PathVariable Long bannerId) {
        homeBannerService.enable(bannerId);
        return ApiResponse.success();
    }

    @PostMapping("/{bannerId}/disable")
    public ApiResponse<Void> disable(@PathVariable Long bannerId) {
        homeBannerService.disable(bannerId);
        return ApiResponse.success();
    }
}
