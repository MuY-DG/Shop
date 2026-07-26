package org.muybaby.shopserver.admin.log.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public record AdminSystemLogQuery(
        Long current,
        Long size,
        String type,
        String result,
        String module,
        String operator,
        String clientIp,
        String requestId,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime occurredStart,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime occurredEnd
) {
}
