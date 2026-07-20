package org.muybaby.shopserver.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("app_contact_setting")
public record ContactSetting(
        @TableId(type = IdType.INPUT) Integer id,
        @TableField("phone_number") String phoneNumber,
        @TableField("updated_at") LocalDateTime updatedAt
) {
}
