package org.muybaby.shopserver.admin.rbac.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("admin_permission")
public record AdminPermission(
        Long id,
        String authMark,
        String title,
        LocalDateTime createdAt
) {
}
