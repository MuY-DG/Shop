package org.muybaby.shopserver.content;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.content.dto.ContactResponse;
import org.muybaby.shopserver.content.service.PublicContentCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/contact")
public class AppContactController {

    private final PublicContentCacheService cacheService;

    public AppContactController(PublicContentCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping
    public ApiResponse<ContactResponse> contact() {
        return ApiResponse.success(cacheService.contact());
    }
}
