package org.muybaby.shopserver.admin.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_menu")
public record AdminMenu(
        Long id,
        Long parentId,
        String name,
        String path,
        String component,
        String title,
        String icon,
        Integer sortOrder,
        Boolean keepAlive,
        Boolean visible,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
