package org.muybaby.shopserver.user.address.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("user_address")
public record UserAddress(
        @TableId(type = IdType.INPUT) Long id,
        Long userId,
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        @TableField("is_default") Boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
