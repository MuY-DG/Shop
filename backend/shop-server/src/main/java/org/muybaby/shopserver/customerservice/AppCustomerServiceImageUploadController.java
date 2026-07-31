package org.muybaby.shopserver.customerservice;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.MessageResponse;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/app/customer-service/images")
public class AppCustomerServiceImageUploadController {

    private final CustomerServiceService customerServiceService;

    public AppCustomerServiceImageUploadController(
            CustomerServiceService customerServiceService
    ) {
        this.customerServiceService = customerServiceService;
    }

    @PostMapping("/upload-sessions")
    public ApiResponse<DirectUploadSessionResponse> createUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody DirectUploadSessionRequest request
    ) {
        return ApiResponse.success(
                customerServiceService.createImageUploadSessionFromApp(
                        principal, request));
    }

    @PostMapping("/upload-sessions/{uploadId}/complete")
    public ApiResponse<MessageResponse> completeUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String uploadId
    ) {
        return ApiResponse.success(
                customerServiceService.completeImageUploadSessionFromApp(
                        principal, uploadId));
    }

    @DeleteMapping("/upload-sessions/{uploadId}")
    public ApiResponse<Void> cancelUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable String uploadId
    ) {
        customerServiceService.cancelImageUploadSessionFromApp(
                principal, uploadId);
        return ApiResponse.success();
    }
}
