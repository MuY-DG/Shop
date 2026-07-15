package org.muybaby.shopserver.customerservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;
import java.util.List;

public final class CustomerServiceDtos {

    private CustomerServiceDtos() {
    }

    public record OpenConversationRequest(
            @Size(max = 20) String contextType,
            @Positive Long contextId,
            @Positive Long orderId
    ) {
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 2000) String content,
            @NotBlank @Size(max = 64) String clientMessageId
    ) {
    }

    public record TransferRequest(
            @NotNull @Positive Long targetAdminUserId,
            @NotBlank @Size(max = 40) String reasonCode,
            @Size(max = 200) String reasonNote
    ) {
    }

    public record AgentWorkStatusRequest(
            @NotBlank @Pattern(regexp = "AVAILABLE|BUSY") String workStatus
    ) {
    }

    public record MessageResponse(
            Long messageId,
            Long conversationId,
            int consultationNo,
            String senderType,
            @JsonStringId Long senderId,
            String senderName,
            String messageType,
            String content,
            Long resourceId,
            LinkedOrderResponse order,
            LinkedProductResponse product,
            ImageMessageResponse image,
            String clientMessageId,
            LocalDateTime createdAt
    ) {
    }

    public record ImageMessageResponse(
            String originalFilename,
            String contentType,
            Integer width,
            Integer height
    ) {
    }

    public record LinkedOrderResponse(
            Long orderId,
            String orderNo,
            String status,
            long payableAmountCent,
            String primaryProductTitle,
            String primaryProductImage,
            int itemCount,
            LocalDateTime createdAt
    ) {
    }

    public record LinkedProductResponse(
            Long productId,
            String title,
            String image,
            Long minPriceCent,
            Long maxPriceCent,
            String status
    ) {
    }

    public record ConsultationContextResponse(
            String type,
            Long resourceId,
            LinkedOrderResponse order,
            LinkedProductResponse product
    ) {
    }

    public record ConversationSummaryResponse(
            Long conversationId,
            @JsonStringId Long appUserId,
            String userNickname,
            String status,
            Long assignedAdminUserId,
            String assignedAdminDisplayName,
            String lastMessagePreview,
            LocalDateTime lastMessageAt,
            int appUnreadCount,
            int adminUnreadCount,
            LocalDateTime claimedAt,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            int consultationNo,
            ConsultationContextResponse currentContext
    ) {
    }

    public record ConversationDetailResponse(
            Long conversationId,
            @JsonStringId Long appUserId,
            String userNickname,
            String status,
            Long assignedAdminUserId,
            String assignedAdminDisplayName,
            String lastMessagePreview,
            LocalDateTime lastMessageAt,
            int appUnreadCount,
            int adminUnreadCount,
            LocalDateTime claimedAt,
            LocalDateTime closedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            int consultationNo,
            ConsultationContextResponse currentContext,
            List<MessageResponse> messages,
            List<LinkedOrderResponse> linkedOrders,
            List<LinkedProductResponse> linkedProducts
    ) {
    }

    public record AgentResponse(
            Long adminUserId,
            String username,
            String displayName,
            String avatar,
            boolean online,
            String workStatus,
            int activeConversationCount,
            int maxActiveConversations,
            boolean canReceive
    ) {
    }

    public record AgentStateResponse(
            Long adminUserId,
            boolean online,
            String workStatus,
            int activeConversationCount,
            int maxActiveConversations,
            boolean canReceive
    ) {
    }

    public record TransferRequestResponse(
            Long requestId,
            Long conversationId,
            @JsonStringId Long appUserId,
            String userNickname,
            String lastMessagePreview,
            ConsultationContextResponse currentContext,
            Long fromAdminUserId,
            String fromAdminDisplayName,
            Long toAdminUserId,
            String toAdminDisplayName,
            String status,
            String reasonCode,
            String reasonNote,
            LocalDateTime expiresAt,
            LocalDateTime resolvedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
