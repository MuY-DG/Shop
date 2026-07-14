package org.muybaby.shopserver.product.dto;

public record AdminSpuQueryRequest(
        Long categoryId,
        String title,
        String status,
        Boolean recycled,
        Long current,
        Long size
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 20 : Math.min(size, 100);
    }
}
