package org.muybaby.shopserver.content;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.AppDisplayConfigResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/display-config")
public class AppDisplayConfigController {
    private final DisplayNameProvider displayNameProvider;

    public AppDisplayConfigController(DisplayNameProvider displayNameProvider) {
        this.displayNameProvider = displayNameProvider;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AppDisplayConfigResponse>> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new AppDisplayConfigResponse(displayNameProvider.displayName())));
    }
}
