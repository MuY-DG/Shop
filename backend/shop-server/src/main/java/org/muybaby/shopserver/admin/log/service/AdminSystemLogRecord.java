package org.muybaby.shopserver.admin.log.service;

import org.muybaby.shopserver.admin.log.AdminSystemLogLevel;
import org.muybaby.shopserver.admin.log.AdminSystemLogResult;
import org.muybaby.shopserver.admin.log.AdminSystemLogType;

import java.time.LocalDateTime;

public record AdminSystemLogRecord(
        AdminSystemLogType type,
        AdminSystemLogResult result,
        AdminSystemLogLevel level,
        Long operatorUserId,
        String operatorUsername,
        String module,
        String action,
        String requestMethod,
        String requestPath,
        String requestPattern,
        int statusCode,
        long durationMs,
        String clientIp,
        String userAgent,
        String requestId,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt
) {
}
