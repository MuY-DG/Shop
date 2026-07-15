package org.muybaby.shopserver.order.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

public record AdminOrderQueryRequest(
        Long current,
        Long size,
        String orderNo,
        String status,
        String statusGroup,
        String userSearchType,
        String userKeyword,
        String receiverName,
        String receiverPhone,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdStart,
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdEnd,
        String trackingNo
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
