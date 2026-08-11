package org.muybaby.shopserver.wechat.servicecard.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminWechatServiceCardDeliveryResponse(
        @JsonStringId Long id,
        @JsonStringId Long cardId,
        @JsonStringId Long orderId,
        int sequenceNo,
        int targetStatus,
        String state,
        boolean cardSendBlocked,
        String cardSendBlockReason,
        LocalDateTime cardSendBlockedAt,
        int setAttempts,
        int reconciliationAttempts,
        int notAppliedObservations,
        String errorCode,
        String errorMessage,
        LocalDateTime nextActionAt,
        LocalDateTime appliedAt,
        String messageResultState,
        Integer messageFailureCode,
        String messageFailureMessage,
        LocalDateTime messageResultAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
