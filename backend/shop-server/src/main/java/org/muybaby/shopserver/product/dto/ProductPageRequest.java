package org.muybaby.shopserver.product.dto;

public record ProductPageRequest(Long categoryId, String keyword, Long current, Long size) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 ? 10 : Math.min(size, 50);
    }
}
