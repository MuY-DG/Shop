package org.muybaby.shopserver.product.dto;

public record ProductImageResponse(Long id, String url, Long fileId, Integer sortOrder) {
}
