package org.muybaby.shopserver.location;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.location.dto.AppAmapConfigResponse;
import org.muybaby.shopserver.location.service.AppLocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/location")
public class AppLocationController {

    private final AppLocationService locationService;

    public AppLocationController(AppLocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/config")
    public ApiResponse<AppAmapConfigResponse> config() {
        return ApiResponse.success(locationService.clientConfig());
    }
}
