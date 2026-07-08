package org.muybaby.shopserver.aftersale.dto;

public record AdminAfterSaleQueryRequest(
        Long current,
        Long size,
        String status,
        String orderNo
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
