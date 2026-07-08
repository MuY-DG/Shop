package org.muybaby.shopserver.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("product_spu_image")
public record ProductSpuImage(
        @TableId(type = IdType.AUTO) Long id,
        Long spuId,
        String url,
        Long fileId,
        Integer sortOrder,
        LocalDateTime createdAt
) {
}
