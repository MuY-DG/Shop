package org.muybaby.shopserver.content;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.AppHomeBannerResponse;
import org.muybaby.shopserver.content.service.HomeBannerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/app/home/banners")
public class AppHomeBannerController {

    private final HomeBannerService homeBannerService;

    public AppHomeBannerController(HomeBannerService homeBannerService) {
        this.homeBannerService = homeBannerService;
    }

    @GetMapping
    public ApiResponse<List<AppHomeBannerResponse>> list() {
        return ApiResponse.success(homeBannerService.appBanners());
    }
}
