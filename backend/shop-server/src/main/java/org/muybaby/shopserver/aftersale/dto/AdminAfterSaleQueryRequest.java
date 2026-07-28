package org.muybaby.shopserver.aftersale.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public record AdminAfterSaleQueryRequest(
        Long current,
        Long size,
        String status,
        String statusGroup,
        Long afterSaleId,
        String afterSaleNo,
        String orderNo,
        String userSearchType,
        String userKeyword,
        String afterSaleType,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdStart,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdEnd,
        String refundNo
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
