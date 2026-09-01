package org.muybaby.shopserver.admin.log.dto;

import org.muybaby.shopserver.common.api.JsonStringId;

import java.time.LocalDateTime;

public record AdminSystemLogResponse(
        @JsonStringId Long id,
        String type,
        String level,
        String result,
        String eventCode,
        String summary,
        String targetType,
        String targetId,
        String relatedTargetType,
        String relatedTargetId,
        String module,
        String action,
        @JsonStringId Long operatorUserId,
        String operatorUsername,
        String requestMethod,
        String requestPath,
        String requestPattern,
        String requestId,
        String clientIp,
        String userAgent,
        Integer statusCode,
        Long durationMs,
        String errorCode,
        String providerErrorCode,
        String errorMessage,
        LocalDateTime createdAt
) {
}
