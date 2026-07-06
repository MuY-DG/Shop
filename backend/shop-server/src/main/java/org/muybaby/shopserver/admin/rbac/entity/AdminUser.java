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
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean enabled() {
        return "ENABLED".equals(status);
    }
}
