package org.muybaby.shopserver.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_user")
public record AppUser(
        Long id,
        String openid,
        String unionid,
        String phoneNumber,
        String phoneCountryCode,
        Boolean phoneAuthorized,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
