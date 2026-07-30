package org.muybaby.shopserver.customerservice;

import jakarta.validation.Valid;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.ConversationDetailResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.LinkedOrderResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.LinkedProductResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.MessageResponse;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.OpenConversationRequest;
import org.muybaby.shopserver.customerservice.dto.CustomerServiceDtos.SendMessageRequest;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/app/customer-service/conversation")
public class AppCustomerServiceController {

    private final CustomerServiceService customerServiceService;

    public AppCustomerServiceController(CustomerServiceService customerServiceService) {
        this.customerServiceService = customerServiceService;
    }

    @GetMapping
    public ApiResponse<ConversationDetailResponse> current(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(customerServiceService.currentForApp(principal));
    }

    @PostMapping("/open")
    public ApiResponse<ConversationDetailResponse> open(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody(required = false) OpenConversationRequest request
    ) {
        return ApiResponse.success(customerServiceService.openForApp(
                principal,
                request == null ? null : request.contextType(),
                request == null ? null : request.contextId(),
                request == null ? null : request.orderId()
        ));
    }

    @GetMapping("/messages")
    public ApiResponse<List<MessageResponse>> messages(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) Long afterId
    ) {
        return ApiResponse.success(customerServiceService.messagesForApp(principal, afterId));
    }

    @PostMapping("/messages")
    public ApiResponse<MessageResponse> send(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return ApiResponse.success(customerServiceService.sendFromApp(principal, request));
    }

    @PostMapping("/images")
    public ApiResponse<MessageResponse> image(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.success(customerServiceService.sendImageFromApp(principal, file));
    }

    @PostMapping("/orders/{orderId}")
    public ApiResponse<LinkedOrderResponse> linkOrder(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long orderId
    ) {
        return ApiResponse.success(customerServiceService.linkOrderFromApp(principal, orderId));
    }

    @GetMapping("/order-candidates")
    public ApiResponse<List<LinkedOrderResponse>> orderCandidates(
            @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ApiResponse.success(customerServiceService.orderCandidatesForApp(principal));
    }

    @PostMapping("/products/{productId}")
    public ApiResponse<LinkedProductResponse> linkProduct(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long productId
    ) {
        return ApiResponse.success(customerServiceService.linkProductFromApp(principal, productId));
    }

    @GetMapping("/product-candidates")
    public ApiResponse<List<LinkedProductResponse>> productCandidates(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(customerServiceService.productCandidatesForApp(principal, keyword));
    }

    @GetMapping("/messages/{messageId}/image")
    public ResponseEntity<InputStreamResource> image(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable Long messageId
    ) {
        return customerServiceService.imageForApp(principal, messageId);
    }
}
