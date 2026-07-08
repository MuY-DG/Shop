package org.muybaby.shopserver.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicFileController {

    private static final String PUBLIC_PREFIX = "/files/public/";

    private final StorageService storageService;

    public PublicFileController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/files/public/**")
    public ResponseEntity<InputStreamResource> publicFile(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }
        String publicPath = requestUri.startsWith(PUBLIC_PREFIX) ? requestUri.substring(PUBLIC_PREFIX.length()) : "";
        return storageService.publicResource(publicPath);
    }
}
