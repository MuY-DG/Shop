package org.muybaby.shopserver.product.dto;

public record AdminGuaranteeServiceQueryRequest(
        Long current,
        Long size,
        String name,
        Boolean visible
) {
    public long pageCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public long pageSize() {
        return size == null || size < 1 || size > 100 ? 20 : size;
    }
}
