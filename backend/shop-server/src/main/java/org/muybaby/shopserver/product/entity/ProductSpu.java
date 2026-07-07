package org.muybaby.shopserver.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("product_spu")
public record ProductSpu(
        @TableId(type = IdType.AUTO) Long id,
        Long categoryId,
        String title,
        String subtitle,
        String mainImage,
        String sellingPoints,
        String detailHtml,
        Integer sortOrder,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
