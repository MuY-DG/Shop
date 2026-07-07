package org.muybaby.shopserver.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("product_category")
public record ProductCategory(
        @TableId(type = IdType.AUTO) Long id,
        Long parentId,
        String name,
        String icon,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
