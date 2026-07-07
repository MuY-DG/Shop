package org.muybaby.shopserver.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("stock_log")
public record StockLog(
        @TableId(type = IdType.AUTO) Long id,
        Long skuId,
        String changeType,
        Integer quantityBefore,
        Integer quantityDelta,
        Integer quantityAfter,
        String reason,
        String operatorType,
        Long operatorId,
        LocalDateTime createdAt
) {
}
