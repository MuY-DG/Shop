package org.muybaby.shopserver.content;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.AppHomeResponse;
import org.muybaby.shopserver.content.service.PublicContentCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/home")
public class AppHomeController {

    private final PublicContentCacheService cacheService;

    public AppHomeController(PublicContentCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping
    public ApiResponse<AppHomeResponse> home() {
        return ApiResponse.success(cacheService.homePage());
    }
}
