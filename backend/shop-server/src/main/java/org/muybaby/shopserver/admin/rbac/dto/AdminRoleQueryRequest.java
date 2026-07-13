package org.muybaby.shopserver.admin.rbac.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record AdminRoleQueryRequest(
        Long current,
        Long size,
        String name,
        String code,
        Boolean enabled,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startTime,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endTime
) {
}
