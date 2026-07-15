package org.muybaby.shopserver.content.dto;

public record AdminHomeProductOptionQuery(
        String keyword,
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
