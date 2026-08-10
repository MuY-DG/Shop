package org.muybaby.shopserver.accountrights.dto;

public record AdminAccountRightsQuery(
        Long current,
        Long size,
        Long userId,
        String requestType,
        String status
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 100);
    }
}
