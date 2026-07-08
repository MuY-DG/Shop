package org.muybaby.shopserver.storage;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageFileResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/app/files")
public class AppFileController {

    private final StorageService storageService;

    public AppFileController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping("/upload")
    public ApiResponse<StorageFileResponse> upload(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam String purpose,
            @RequestParam(required = false) Long assetCategoryId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(storageService.uploadApp(principal, purpose, assetCategoryId, file));
    }
}
