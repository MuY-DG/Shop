package org.muybaby.shopserver.user.dto;

public record AdminCustomerQueryRequest(
        Long current,
        Long size,
        String keyword,
        String status
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 100);
    }
}
