package org.muybaby.shopserver.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("product_sku")
public record ProductSku(
        @TableId(type = IdType.AUTO) Long id,
        Long spuId,
        String skuCode,
        String specJson,
        String specText,
        Long priceCent,
        Long originalPriceCent,
        Long costPriceCent,
        Integer stockAvailable,
        Integer lowStockThreshold,
        Integer weightGram,
        String netContentText,
        BigDecimal volumeCubicMeter,
        String image,
        Long imageFileId,
        String status,
        @TableField("is_default") Boolean defaultSelected,
        String combinationKey,
        Integer sortOrder,
        LocalDateTime deletedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
