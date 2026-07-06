package org.muybaby.shopserver.admin.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_role")
public record AdminRole(
        Long id,
        String code,
        String name,
        String description,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
