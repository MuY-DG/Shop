package org.muybaby.shopserver.order.dto;

public record AdminOrderQueryRequest(
        Long current,
        Long size,
        String orderNo,
        String status
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
