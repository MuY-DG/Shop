package org.muybaby.shopserver.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_user")
public record AppUser(
        Long id,
        String openid,
        String unionid,
        String nickname,
        String avatarUrl,
        String phoneNumber,
        String phoneCountryCode,
        Boolean phoneAuthorized,
        String status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long authVersion,
        LocalDateTime cancelledAt
) {

    public AppUser(
            Long id,
            String openid,
            String unionid,
            String nickname,
            String avatarUrl,
            String phoneNumber,
            String phoneCountryCode,
            Boolean phoneAuthorized,
            String status,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(
                id, openid, unionid, nickname, avatarUrl, phoneNumber, phoneCountryCode,
                phoneAuthorized, status, lastLoginAt, createdAt, updatedAt, 0L, null
        );
    }
}
