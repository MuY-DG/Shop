package org.muybaby.shopserver.content;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.content.dto.AdminHomeCategoryOptionResponse;
import org.muybaby.shopserver.content.dto.AdminHomeProductOptionQuery;
import org.muybaby.shopserver.content.dto.AdminHomeProductOptionResponse;
import org.muybaby.shopserver.content.service.HomeDecorationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/home/options")
public class AdminHomeOptionController {

    private static final String DECORATION_AUTHORITIES = "hasAnyAuthority(" +
            "'content:home-category:read', 'content:home-category:write', " +
            "'content:home-hot:read', 'content:home-hot:write', " +
            "'content:home-recommended:read', 'content:home-recommended:write')";

    private final HomeDecorationService service;

    public AdminHomeOptionController(HomeDecorationService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    @PreAuthorize(DECORATION_AUTHORITIES)
    public ApiResponse<List<AdminHomeCategoryOptionResponse>> categories() {
        return ApiResponse.success(service.categoryOptions());
    }

    @GetMapping("/products")
    @PreAuthorize(DECORATION_AUTHORITIES)
    public ApiResponse<PageResult<AdminHomeProductOptionResponse>> products(AdminHomeProductOptionQuery query) {
        return ApiResponse.success(service.productOptions(query));
    }
}
