package org.muybaby.shopserver.customerservice;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.AutoReplyConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.CommonAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.OfflineAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyConfigResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyCreateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyGroupCreateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyGroupResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.QuickReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.SmartAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceReplyDtos.WelcomeAutoReplyUpdateRequest;
import org.muybaby.shopserver.customerservice.service.CustomerServiceReplyService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/customer-service")
public class AdminCustomerServiceReplyController {

    private final CustomerServiceReplyService replyService;

    public AdminCustomerServiceReplyController(CustomerServiceReplyService replyService) {
        this.replyService = replyService;
    }

    @GetMapping("/auto-replies")
    @PreAuthorize("hasAuthority('customer-service:auto-reply:read')")
    public ApiResponse<AutoReplyConfigResponse> autoReplies(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(replyService.autoReplies(principal.subjectId()));
    }

    @PutMapping("/auto-replies/common")
    @PreAuthorize("hasAuthority('customer-service:auto-reply:update')")
    public ApiResponse<AutoReplyConfigResponse> updateCommon(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CommonAutoReplyUpdateRequest request
    ) {
        return ApiResponse.success(replyService.updateCommon(principal.subjectId(), request));
    }

    @PutMapping("/auto-replies/welcome")
    @PreAuthorize("hasAuthority('customer-service:auto-reply:welcome:update')")
    public ApiResponse<AutoReplyConfigResponse> updateWelcome(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody WelcomeAutoReplyUpdateRequest request
    ) {
        return ApiResponse.success(replyService.updateWelcome(principal.subjectId(), request));
    }

    @PutMapping("/auto-replies/offline")
    @PreAuthorize("hasAuthority('customer-service:auto-reply:update')")
    public ApiResponse<AutoReplyConfigResponse> updateOffline(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody OfflineAutoReplyUpdateRequest request
    ) {
        return ApiResponse.success(replyService.updateOffline(principal.subjectId(), request));
    }

    @PutMapping("/auto-replies/smart")
    @PreAuthorize("hasAuthority('customer-service:auto-reply:update')")
    public ApiResponse<AutoReplyConfigResponse> updateSmart(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody SmartAutoReplyUpdateRequest request
    ) {
        return ApiResponse.success(replyService.updateSmart(principal.subjectId(), request));
    }

    @GetMapping("/quick-replies")
    @PreAuthorize("hasAuthority('customer-service:quick-reply:read')")
    public ApiResponse<QuickReplyConfigResponse> quickReplies() {
        return ApiResponse.success(replyService.quickReplies());
    }

    @PostMapping("/quick-reply-groups")
    @PreAuthorize("hasAuthority('customer-service:quick-reply:update')")
    public ApiResponse<QuickReplyGroupResponse> createQuickReplyGroup(
            @Valid @RequestBody QuickReplyGroupCreateRequest request
    ) {
        return ApiResponse.success(replyService.createQuickReplyGroup(request));
    }

    @PostMapping("/quick-replies")
    @PreAuthorize("hasAuthority('customer-service:quick-reply:update')")
    public ApiResponse<QuickReplyResponse> createQuickReply(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody QuickReplyCreateRequest request
    ) {
        return ApiResponse.success(replyService.createQuickReply(principal.subjectId(), request));
    }

    @PutMapping("/quick-replies/{replyId}")
    @PreAuthorize("hasAuthority('customer-service:quick-reply:update')")
    public ApiResponse<QuickReplyResponse> updateQuickReply(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long replyId,
            @Valid @RequestBody QuickReplyUpdateRequest request
    ) {
        return ApiResponse.success(replyService.updateQuickReply(
                principal.subjectId(), replyId, request));
    }

    @DeleteMapping("/quick-replies/{replyId}")
    @PreAuthorize("hasAuthority('customer-service:quick-reply:update')")
    public ApiResponse<Void> deleteQuickReply(@PathVariable Long replyId) {
        replyService.deleteQuickReply(replyId);
        return ApiResponse.success();
    }
}
