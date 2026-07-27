package org.muybaby.shopserver.admin.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_user")
public record AdminUser(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String email,
        String avatar,
        String status,
        int maxSessions,
        long authVersion,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public AdminUser(
            Long id,
            String username,
            String passwordHash,
            String displayName,
            String email,
            String avatar,
            String status,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(
                id,
                username,
                passwordHash,
                displayName,
                email,
                avatar,
                status,
                0,
                0L,
                lastLoginAt,
                createdAt,
                updatedAt
        );
    }

    public boolean enabled() {
        return "ENABLED".equals(status);
    }
}
