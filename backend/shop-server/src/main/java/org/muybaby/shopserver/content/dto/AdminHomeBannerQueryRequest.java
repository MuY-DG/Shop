package org.muybaby.shopserver.content.dto;

public record AdminHomeBannerQueryRequest(
        Long current,
        Long size,
        String title,
        String status
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
