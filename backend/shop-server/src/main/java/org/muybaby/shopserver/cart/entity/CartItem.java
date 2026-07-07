package org.muybaby.shopserver.cart.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("cart_item")
public record CartItem(
        @TableId(type = IdType.AUTO) Long id,
        Long userId,
        Long skuId,
        Integer quantity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
