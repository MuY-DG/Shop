package org.muybaby.shopserver.customerservice;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.AgentResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.AgentStateResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.AgentProfileResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.AgentWorkStatusRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationDetailResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationSummaryResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationWorkspaceResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ImageMessageResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.LinkedOrderResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.LinkedProductResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.MessageResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.PersonalSettingsResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.PersonalSettingsUpdateRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.SendMessageRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.TransferRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.TransferRequestResponse;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ReportQuery;
import org.muybaby.shopserver.operation.dto.OperationsStatisticsDtos.ServiceStatisticsReport;
import org.muybaby.shopserver.operation.service.OperationsStatisticsService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/admin/customer-service")
public class AdminCustomerServiceController {

    private final CustomerServiceService customerServiceService;
    private final OperationsStatisticsService operationsStatisticsService;

    public AdminCustomerServiceController(
            CustomerServiceService customerServiceService,
            OperationsStatisticsService operationsStatisticsService
    ) {
        this.customerServiceService = customerServiceService;
        this.operationsStatisticsService = operationsStatisticsService;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<ServiceStatisticsReport> overview(ReportQuery query) {
        return ApiResponse.success(operationsStatisticsService.serviceStatistics(query));
    }

    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<PageResult<ConversationSummaryResponse>> conversations(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            String status,
            String keyword,
            Long current,
            Long size
    ) {
        return ApiResponse.success(
                customerServiceService.adminPage(principal, status, keyword, current, size)
        );
    }

    @GetMapping("/conversations/workspace")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<ConversationWorkspaceResponse> workspace(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            String keyword
    ) {
        return ApiResponse.success(customerServiceService.adminWorkspace(principal, keyword));
    }

    @GetMapping("/conversations/{conversationId}")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<ConversationDetailResponse> detail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ApiResponse.success(customerServiceService.adminDetail(principal, conversationId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<List<MessageResponse>> messages(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(
                customerServiceService.messagesForAdmin(
                        principal, conversationId, afterId, beforeId, limit
                )
        );
    }

    @PostMapping("/conversations/{conversationId}/claim")
    @PreAuthorize("hasAuthority('customer-service:conversation:claim')")
    public ApiResponse<ConversationDetailResponse> claim(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ApiResponse.success(customerServiceService.claim(principal, conversationId));
    }

    @PostMapping("/conversations/{conversationId}/transfer-requests")
    @PreAuthorize("hasAuthority('customer-service:conversation:transfer')")
    public ApiResponse<TransferRequestResponse> requestTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody TransferRequest request
    ) {
        return ApiResponse.success(customerServiceService.requestTransfer(principal, conversationId, request));
    }

    @GetMapping("/transfer-requests/pending")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<List<TransferRequestResponse>> pendingTransferRequests(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(customerServiceService.pendingTransferRequests(principal));
    }

    @PostMapping("/transfer-requests/{requestId}/accept")
    @PreAuthorize("hasAuthority('customer-service:conversation:transfer')")
    public ApiResponse<ConversationDetailResponse> acceptTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId
    ) {
        return ApiResponse.success(customerServiceService.acceptTransfer(principal, requestId));
    }

    @PostMapping("/transfer-requests/{requestId}/reject")
    @PreAuthorize("hasAuthority('customer-service:conversation:transfer')")
    public ApiResponse<TransferRequestResponse> rejectTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long requestId
    ) {
        return ApiResponse.success(customerServiceService.rejectTransfer(principal, requestId));
    }

    @PostMapping("/conversations/{conversationId}/release")
    @PreAuthorize("hasAnyAuthority('customer-service:conversation:transfer', 'customer-service:agent:manage')")
    public ApiResponse<ConversationDetailResponse> release(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ApiResponse.success(customerServiceService.release(principal, conversationId));
    }

    @PostMapping("/conversations/{conversationId}/force-transfer")
    @PreAuthorize("hasAuthority('customer-service:agent:manage')")
    public ApiResponse<ConversationDetailResponse> forceTransfer(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody TransferRequest request
    ) {
        return ApiResponse.success(customerServiceService.forceTransfer(principal, conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/close")
    @PreAuthorize("hasAuthority('customer-service:conversation:close')")
    public ApiResponse<ConversationDetailResponse> close(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ApiResponse.success(customerServiceService.close(principal, conversationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    @PreAuthorize("hasAuthority('customer-service:message:send')")
    public ApiResponse<MessageResponse> send(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return ApiResponse.success(customerServiceService.sendFromAdmin(principal, conversationId, request));
    }

    @GetMapping("/conversations/{conversationId}/order-candidates")
    @PreAuthorize("hasAnyAuthority('customer-service:conversation:read', 'customer-service:order:link')")
    public ApiResponse<List<LinkedOrderResponse>> orderCandidates(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId
    ) {
        return ApiResponse.success(customerServiceService.orderCandidates(principal, conversationId));
    }

    @PostMapping("/conversations/{conversationId}/orders/{orderId}")
    @PreAuthorize("hasAuthority('customer-service:order:link')")
    public ApiResponse<LinkedOrderResponse> linkOrder(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(customerServiceService.linkOrderFromAdmin(principal, conversationId, orderId));
    }

    @GetMapping("/conversations/{conversationId}/product-candidates")
    @PreAuthorize("hasAnyAuthority('customer-service:conversation:read', 'customer-service:product:send')")
    public ApiResponse<List<LinkedProductResponse>> productCandidates(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(
                customerServiceService.productCandidates(principal, conversationId, keyword)
        );
    }

    @PostMapping("/conversations/{conversationId}/products/{productId}")
    @PreAuthorize("hasAuthority('customer-service:product:send')")
    public ApiResponse<LinkedProductResponse> linkProduct(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @PathVariable Long productId
    ) {
        return ApiResponse.success(
                customerServiceService.linkProductFromAdmin(principal, conversationId, productId)
        );
    }

    @PostMapping("/conversations/{conversationId}/images")
    @PreAuthorize("hasAuthority('customer-service:message:send')")
    public ApiResponse<MessageResponse> image(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(customerServiceService.sendImageFromAdmin(principal, conversationId, file));
    }

    @PostMapping("/conversations/{conversationId}/images/upload-sessions")
    @PreAuthorize("hasAuthority('customer-service:message:send')")
    public ApiResponse<DirectUploadSessionResponse> createImageUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @Valid @RequestBody DirectUploadSessionRequest request
    ) {
        return ApiResponse.success(
                customerServiceService.createImageUploadSessionFromAdmin(
                        principal, conversationId, request));
    }

    @PostMapping("/conversations/{conversationId}/images/upload-sessions/{uploadId}/complete")
    @PreAuthorize("hasAuthority('customer-service:message:send')")
    public ApiResponse<MessageResponse> completeImageUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @PathVariable String uploadId
    ) {
        return ApiResponse.success(
                customerServiceService.completeImageUploadSessionFromAdmin(
                        principal, conversationId, uploadId));
    }

    @DeleteMapping("/conversations/{conversationId}/images/upload-sessions/{uploadId}")
    @PreAuthorize("hasAuthority('customer-service:message:send')")
    public ApiResponse<Void> cancelImageUploadSession(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long conversationId,
            @PathVariable String uploadId
    ) {
        customerServiceService.cancelImageUploadSessionFromAdmin(
                principal, conversationId, uploadId);
        return ApiResponse.success();
    }

    @GetMapping("/messages/{messageId}/image")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ResponseEntity<InputStreamResource> image(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long messageId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        return customerServiceService.imageForAdmin(principal, messageId, ifNoneMatch);
    }

    @GetMapping("/messages/{messageId}/thumbnail")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ResponseEntity<InputStreamResource> thumbnail(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long messageId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        return customerServiceService.thumbnailForAdmin(principal, messageId, ifNoneMatch);
    }

    @GetMapping("/messages/{messageId}/image-access")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<ImageMessageResponse> imageAccess(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long messageId
    ) {
        return ApiResponse.success(customerServiceService.imageAccessForAdmin(principal, messageId));
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<List<AgentResponse>> agents() {
        return ApiResponse.success(customerServiceService.agents());
    }

    @GetMapping("/agent-state")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<AgentStateResponse> agentState(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(customerServiceService.agentState(principal));
    }

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<AgentProfileResponse> profile(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(customerServiceService.agentProfile(principal));
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('customer-service:settings:update')")
    public ApiResponse<PersonalSettingsResponse> settings(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(customerServiceService.personalSettings(principal));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('customer-service:settings:update')")
    public ApiResponse<PersonalSettingsResponse> updateSettings(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody PersonalSettingsUpdateRequest request
    ) {
        return ApiResponse.success(customerServiceService.updatePersonalSettings(principal, request));
    }

    @PutMapping("/agent-state")
    @PreAuthorize("hasAuthority('customer-service:conversation:read')")
    public ApiResponse<AgentStateResponse> updateAgentState(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody AgentWorkStatusRequest request
    ) {
        return ApiResponse.success(customerServiceService.updateAgentState(principal, request.workStatus()));
    }
}
